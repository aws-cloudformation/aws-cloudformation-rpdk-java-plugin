/*
* Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License").
* You may not use this file except in compliance with the License.
* A copy of the License is located at
*
*  http://aws.amazon.com/apache2.0
*
* or in the "license" file accompanying this file. This file is distributed
* on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
* express or implied. See the License for the specific language governing
* permissions and limitations under the License.
*/
package software.amazon.cloudformation.proxy;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.AmazonWebServiceResult;
import com.amazonaws.ResponseMetadata;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.google.common.base.Preconditions;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequest;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.AwsResponse;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.exception.RetryableException;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.cloudformation.exceptions.BaseHandlerException;
import software.amazon.cloudformation.exceptions.TerminalException;

/**
 * This implements the proxying mechanism to inject appropriate scoped
 * credentials into a service call when making Amazon Webservice calls.
 *
 * @see CallChain
 * @see ProxyClient
 */
public class AmazonWebServicesClientProxy implements CallChain {

    public static final int HTTP_STATUS_NETWORK_AUTHN_REQUIRED = 511;
    public static final int HTTP_STATUS_GONE = 410;

    private final AWSCredentialsProvider v1CredentialsProvider;
    private final AwsCredentialsProvider v2CredentialsProvider;
    private final LoggerProxy loggerProxy;
    private final DelayFactory override;
    private final WaitStrategy waitStrategy;
    @Getter
    @Setter
    private Boolean invokedByCfn;

    public AmazonWebServicesClientProxy(final LoggerProxy loggerProxy,
                                        final Credentials credentials,
                                        final Supplier<Long> remainingTimeToExecute) {
        this(loggerProxy, credentials, remainingTimeToExecute, DelayFactory.CONSTANT_DEFAULT_DELAY_FACTORY);
    }

    public AmazonWebServicesClientProxy(final LoggerProxy loggerProxy,
                                        final Credentials credentials,
                                        final Supplier<Long> remainingTimeToExecute,
                                        final DelayFactory override) {
        this(loggerProxy, credentials, override, WaitStrategy.newLocalLoopAwaitStrategy(remainingTimeToExecute), null);
    }

    public AmazonWebServicesClientProxy(final LoggerProxy loggerProxy,
                                        final Credentials credentials,
                                        final DelayFactory override,
                                        final WaitStrategy waitStrategy,
                                        final Boolean invokedByCfn) {
        this.loggerProxy = loggerProxy;
        BasicSessionCredentials basicSessionCredentials = new BasicSessionCredentials(credentials.getAccessKeyId(),
                                                                                      credentials.getSecretAccessKey(),
                                                                                      credentials.getSessionToken());
        this.v1CredentialsProvider = new AWSStaticCredentialsProvider(basicSessionCredentials);

        AwsSessionCredentials awsSessionCredentials = AwsSessionCredentials.create(credentials.getAccessKeyId(),
            credentials.getSecretAccessKey(), credentials.getSessionToken());
        this.v2CredentialsProvider = StaticCredentialsProvider.create(awsSessionCredentials);
        this.override = Objects.requireNonNull(override);
        this.waitStrategy = Objects.requireNonNull(waitStrategy);
        this.invokedByCfn = invokedByCfn;
    }

    public <ClientT> ProxyClient<ClientT> newProxy(@Nonnull Supplier<ClientT> client) {
        return new ProxyClient<ClientT>() {
            @Override
            public <RequestT extends AwsRequest, ResponseT extends AwsResponse>
                ResponseT
                injectCredentialsAndInvokeV2(RequestT request, Function<RequestT, ResponseT> requestFunction) {
                return AmazonWebServicesClientProxy.this.injectCredentialsAndInvokeV2(request, requestFunction);
            }

            @Override
            public <RequestT extends AwsRequest, ResponseT extends AwsResponse>
                CompletableFuture<ResponseT>
                injectCredentialsAndInvokeV2Async(RequestT request,
                                                  Function<RequestT, CompletableFuture<ResponseT>> requestFunction) {
                return AmazonWebServicesClientProxy.this.injectCredentialsAndInvokeV2Async(request, requestFunction);
            }

            @Override
            public <RequestT extends AwsRequest, ResponseT extends AwsResponse, IterableT extends SdkIterable<ResponseT>>
                IterableT
                injectCredentialsAndInvokeIterableV2(RequestT request, Function<RequestT, IterableT> requestFunction) {
                return AmazonWebServicesClientProxy.this.injectCredentialsAndInvokeIterableV2(request, requestFunction);
            }

            @Override
            public <RequestT extends AwsRequest, ResponseT extends AwsResponse>
                ResponseInputStream<ResponseT>
                injectCredentialsAndInvokeV2InputStream(RequestT request,
                                                        Function<RequestT, ResponseInputStream<ResponseT>> requestFunction) {
                return AmazonWebServicesClientProxy.this.injectCredentialsAndInvokeV2InputStream(request, requestFunction);
            }

            @Override
            public <RequestT extends AwsRequest, ResponseT extends AwsResponse>
                ResponseBytes<ResponseT>
                injectCredentialsAndInvokeV2Bytes(RequestT request,
                                                  Function<RequestT, ResponseBytes<ResponseT>> requestFunction) {
                return AmazonWebServicesClientProxy.this.injectCredentialsAndInvokeV2Bytes(request, requestFunction);
            }

            @Override
            public ClientT client() {
                return client.get();
            }
        };
    }

    public <ClientT, ModelT, CallbackT extends StdCallbackContext>
        Initiator<ClientT, ModelT, CallbackT>
        newInitiator(@Nonnull Supplier<ClientT> client, final ModelT model, final CallbackT context) {
        return newInitiator(newProxy(client), model, context);
    }

    @Override
    public <ClientT, ModelT, CallbackT extends StdCallbackContext>
        Initiator<ClientT, ModelT, CallbackT>
        newInitiator(final ProxyClient<ClientT> client, final ModelT model, final CallbackT context) {
        Preconditions.checkNotNull(client, "ProxyClient can not be null");
        Preconditions.checkNotNull(model, "Resource Model can not be null");
        Preconditions.checkNotNull(context, "cxt can not be null");
        return new StdInitiator<>(client, model, context);
    }

    private class StdInitiator<ClientT, ModelT, CallbackT extends StdCallbackContext>
        implements Initiator<ClientT, ModelT, CallbackT> {

        private final ProxyClient<ClientT> client;
        private final ModelT model;
        private final CallbackT callback;

        private StdInitiator(final ProxyClient<ClientT> client,
                             final ModelT model,
                             final CallbackT callback) {
            Preconditions.checkNotNull(client, "ProxyClient can not be null");
            Preconditions.checkNotNull(model, "Resource Model can not be null");
            Preconditions.checkNotNull(callback, "cxt can not be null");
            this.client = client;
            this.model = model;
            this.callback = callback;
        }

        @Override
        public RequestMaker<ClientT, ModelT, CallbackT> initiate(String callGraph) {
            return new CallContext<>(callGraph, client, model, callback);
        }

        @Override
        public <
            RequestT> Caller<RequestT, ClientT, ModelT, CallbackT> translateToServiceRequest(Function<ModelT, RequestT> maker) {
            return initiate("").translateToServiceRequest(maker);
        }

        @Override
        public ModelT getResourceModel() {
            return model;
        }

        @Override
        public CallbackT getCallbackContext() {
            return callback;
        }

        @Override
        public <NewModelT> Initiator<ClientT, NewModelT, CallbackT> rebindModel(NewModelT model) {
            Preconditions.checkNotNull(model, "Resource Model can not be null");
            return new StdInitiator<>(client, model, callback);
        }

        @Override
        public <NewCallbackT extends StdCallbackContext>
            Initiator<ClientT, ModelT, NewCallbackT>
            rebindCallback(NewCallbackT callback) {
            Preconditions.checkNotNull(callback, "cxt can not be null");
            return new StdInitiator<>(client, model, callback);
        }

        @Override
        public Logger getLogger() {
            return AmazonWebServicesClientProxy.this.loggerProxy;
        }
    }

    @Override
    public <ClientT, ModelT, CallbackT extends StdCallbackContext>
        RequestMaker<ClientT, ModelT, CallbackT>
        initiate(String callGraph, ProxyClient<ClientT> client, ModelT model, CallbackT cxt) {
        Preconditions.checkNotNull(callGraph, "callGraph can not be null");
        Preconditions.checkNotNull(client, "ProxyClient can not be null");
        Preconditions.checkNotNull(model, "Resource Model can not be null");
        Preconditions.checkNotNull(cxt, "cxt can not be null");
        return new CallContext<>(callGraph, client, model, cxt);
    }

    class CallContext<ClientT, ModelT, CallbackT extends StdCallbackContext>
        implements CallChain.RequestMaker<ClientT, ModelT, CallbackT> {

        private final String callGraph;
        private final ProxyClient<ClientT> client;
        private final ModelT model;
        private final CallbackT context;
        //
        // Default delay context and retries for all web service calls when
        // handling errors, throttles and more. The handler can influence this
        // using retry method.
        //
        private Delay delay = null;

        CallContext(String callGraph,
                    ProxyClient<ClientT> client,
                    ModelT model,
                    CallbackT context) {
            this.callGraph = Preconditions.checkNotNull(callGraph);
            this.client = Preconditions.checkNotNull(client);
            this.model = Preconditions.checkNotNull(model);
            this.context = context;
        }

        @Override
        public <
            RequestT> Caller<RequestT, ClientT, ModelT, CallbackT> translateToServiceRequest(Function<ModelT, RequestT> maker) {
            return new Caller<RequestT, ClientT, ModelT, CallbackT>() {

                private final CallGraphNameGenerator<ModelT, RequestT, ClientT,
                    CallbackT> generator = (incoming, model_, reqMaker, client_, context_) -> {
                        final RequestT request = reqMaker.apply(model_);
                        String objectHash = String.valueOf(Objects.hashCode(request));
                        String serviceName = (client_ == null
                            ? ""
                            : (client_ instanceof SdkClient)
                                ? ((SdkClient) client_).serviceName()
                                : client_.getClass().getSimpleName());
                        String requestName = request != null ? request.getClass().getSimpleName().replace("Request", "") : "";
                        String callGraph = serviceName + ":" + requestName + "-" + (incoming != null ? incoming : "") + "-"
                            + objectHash;
                        context_.request(callGraph, (ignored -> request)).apply(model_);
                        return callGraph;
                    };

                @Override
                public Caller<RequestT, ClientT, ModelT, CallbackT> backoffDelay(Delay delay) {
                    CallContext.this.delay = delay;
                    return this;
                }

                @Override
                public <ResponseT>
                    Stabilizer<RequestT, ResponseT, ClientT, ModelT, CallbackT>
                    makeServiceCall(BiFunction<RequestT, ProxyClient<ClientT>, ResponseT> caller) {
                    return new Stabilizer<RequestT, ResponseT, ClientT, ModelT, CallbackT>() {

                        private Callback<RequestT, ResponseT, ClientT, ModelT, CallbackT, Boolean> waitFor;
                        // default exception handler, reports failure.
                        private Callback<? super RequestT, Exception, ClientT, ModelT, CallbackT,
                            ProgressEvent<ModelT, CallbackT>> exceptHandler;

                        @Override
                        public Exceptional<RequestT, ResponseT, ClientT, ModelT, CallbackT>
                            stabilize(Callback<RequestT, ResponseT, ClientT, ModelT, CallbackT, Boolean> callback) {
                            this.waitFor = callback;
                            return this;
                        }

                        @Override
                        public Completed<RequestT, ResponseT, ClientT, ModelT, CallbackT> retryErrorFilter(final Callback<
                            ? super RequestT, Exception, ClientT, ModelT, CallbackT, Boolean> retryFilter) {
                            return handleError(((request, exception, client_, model_, context_) -> {
                                if (retryFilter.invoke(request, exception, client_, model_, context_)) {
                                    throw RetryableException.builder().build();
                                }
                                return defaultHandler(request, exception, client_, model_, context_);
                            }));
                        }

                        @Override
                        public Completed<RequestT, ResponseT, ClientT, ModelT, CallbackT> handleError(ExceptionPropagate<
                            ? super RequestT, Exception, ClientT, ModelT, CallbackT, ProgressEvent<ModelT, CallbackT>> handler) {
                            getExceptionHandler(handler);
                            return this;
                        }

                        private
                            Callback<? super RequestT, Exception, ClientT, ModelT, CallbackT, ProgressEvent<ModelT, CallbackT>>
                            getExceptionHandler(final ExceptionPropagate<? super RequestT, Exception, ClientT, ModelT, CallbackT,
                                ProgressEvent<ModelT, CallbackT>> handler) {
                            if (this.exceptHandler == null) {
                                this.exceptHandler = ((request, exception, client_, model_, context_) -> {
                                    ProgressEvent<ModelT, CallbackT> event = null;
                                    Exception ex = exception;
                                    ExceptionPropagate<? super RequestT, Exception, ClientT, ModelT, CallbackT,
                                        ProgressEvent<ModelT, CallbackT>> inner = handler;
                                    boolean defaultHandler = false;
                                    do {
                                        try {
                                            event = inner.invoke(request, ex, client_, model_, context_);

                                            //
                                            // Ensure that we null out model for AlreadyExists to meet CFN IaC expectations
                                            //
                                            if (event.getErrorCode() == HandlerErrorCode.AlreadyExists
                                                && AmazonWebServicesClientProxy.this.invokedByCfn) {
                                                event.setResourceModel(null);
                                            }

                                        } catch (RetryableException e) {
                                            break;
                                        } catch (Exception e) {
                                            if (defaultHandler) {
                                                throw new TerminalException("FRAMEWORK ERROR, LOOPING cause " + e, e);
                                            }
                                            defaultHandler = true;
                                            ex = e;
                                            inner = AmazonWebServicesClientProxy.this::defaultHandler;
                                        }
                                    } while (event == null);
                                    return event;
                                });
                            }
                            return this.exceptHandler;
                        }

                        @Override
                        public ProgressEvent<ModelT, CallbackT> done(Callback<RequestT, ResponseT, ClientT, ModelT, CallbackT,
                            ProgressEvent<ModelT, CallbackT>> callback) {
                            //
                            // StdCallbackContext memoization wrappers for request, response, and
                            // stabilization
                            // lambdas. This ensures that we call demux as necessary.
                            //
                            final String callGraph = generator.callGraph(CallContext.this.callGraph, model, maker,
                                client.client(), context);
                            Delay delay = override.getDelay(callGraph, CallContext.this.delay);
                            Function<ModelT, RequestT> reqMaker = context.request(callGraph, maker);
                            BiFunction<RequestT, ProxyClient<ClientT>, ResponseT> resMaker = context.response(callGraph, caller);
                            if (waitFor != null) {
                                waitFor = context.stabilize(callGraph, waitFor);
                            }
                            int attempt = context.attempts(callGraph);
                            RequestT req = null;
                            ResponseT res = null;
                            ProgressEvent<ModelT, CallbackT> event = null;
                            Callback<? super RequestT, Exception, ClientT, ModelT, CallbackT,
                                ProgressEvent<ModelT, CallbackT>> exceptionHandler = getExceptionHandler(
                                    AmazonWebServicesClientProxy.this::defaultHandler);
                            try {
                                for (;;) {
                                    Instant now = Instant.now();
                                    try {
                                        req = req == null ? reqMaker.apply(model) : req;
                                        res = res == null ? resMaker.apply(req, client) : res;
                                        if (waitFor != null) {
                                            if (waitFor.invoke(req, res, client, model, context)) {
                                                event = callback.invoke(req, res, client, model, context);
                                            }
                                        } else {
                                            event = callback.invoke(req, res, client, model, context);
                                        }
                                    } catch (BaseHandlerException e) {
                                        throw e;
                                    } catch (Exception e) {
                                        event = exceptionHandler.invoke(req, e, client, model, context);
                                    }

                                    if (event != null) {
                                        return event;
                                    }

                                    //
                                    // The logic to wait is if next delay + 2 * time to run the operation sequence +
                                    // 100ms
                                    // is less than time remaining time to run inside Lambda then we locally wait
                                    // else we bail out. Assuming 3 DAYS for a DB to restore, that would be total of
                                    // 3 x 24 x 60 x 60 x 1000 ms, fits in 32 bit int.
                                    //
                                    Instant opTime = Instant.now();
                                    long elapsed = ChronoUnit.MILLIS.between(now, opTime);
                                    Duration next = delay.nextDelay(attempt++);
                                    context.attempts(callGraph, attempt);
                                    if (next == Duration.ZERO) {
                                        return ProgressEvent.failed(model, context, HandlerErrorCode.NotStabilized,
                                            "Exceeded attempts to wait");
                                    }
                                    event = AmazonWebServicesClientProxy.this.waitStrategy.await(elapsed, next, context, model);
                                    if (event != null) {
                                        return event;
                                    }
                                }
                            } finally {
                                //
                                // only set request if response was successful. Otherwise we will remember the
                                // the original failed request in the callback. So when we fix and resume from
                                // the error with callback, we will replay the wrong one
                                //
                                if (res == null) {
                                    context.evictRequestRecord(callGraph);
                                }
                            }
                        }

                        @Override
                        public ProgressEvent<ModelT, CallbackT> done(Function<ResponseT, ProgressEvent<ModelT, CallbackT>> func) {
                            return done((request1, response1, client1, model1, context1) -> func.apply(response1));
                        }
                    };
                }
            };
        }

    }

    public <RequestT extends AmazonWebServiceRequest, ResultT extends AmazonWebServiceResult<ResponseMetadata>>
        ResultT
        injectCredentialsAndInvoke(final RequestT request, final Function<RequestT, ResultT> requestFunction) {

        request.setRequestCredentialsProvider(v1CredentialsProvider);

        try {
            ResultT response = requestFunction.apply(request);
            logRequestMetadata(request, response);
            return response;
        } catch (final Throwable e) {
            loggerProxy.log(String.format("Failed to execute remote function: {%s}", e.getMessage()));
            throw e;
        } finally {
            request.setRequestCredentialsProvider(null);
        }
    }

    public <RequestT extends AmazonWebServiceRequest> void injectCredentialsAndInvoke(final RequestT request,
                                                                                      final Consumer<RequestT> requestFunction) {

        request.setRequestCredentialsProvider(v1CredentialsProvider);

        try {
            requestFunction.accept(request);
        } catch (final Throwable e) {
            loggerProxy.log(String.format("Failed to execute remote function: {%s}", e.getMessage()));
            throw e;
        } finally {
            request.setRequestCredentialsProvider(null);
        }
    }

    public <RequestT extends AwsRequest, ResultT extends AwsResponse>
        ResultT
        injectCredentialsAndInvokeV2(final RequestT request, final Function<RequestT, ResultT> requestFunction) {

        AwsRequestOverrideConfiguration overrideConfiguration = AwsRequestOverrideConfiguration.builder()
            .credentialsProvider(v2CredentialsProvider).build();

        @SuppressWarnings("unchecked")
        RequestT wrappedRequest = (RequestT) request.toBuilder().overrideConfiguration(overrideConfiguration).build();

        try {
            ResultT response = requestFunction.apply(wrappedRequest);
            logRequestMetadataV2(request, response);
            return response;
        } catch (final Throwable e) {
            loggerProxy.log(String.format("Failed to execute remote function: {%s}", e.getMessage()));
            throw e;
        }
    }

    public <RequestT extends AwsRequest, ResultT extends AwsResponse>
        CompletableFuture<ResultT>
        injectCredentialsAndInvokeV2Async(final RequestT request,
                                          final Function<RequestT, CompletableFuture<ResultT>> requestFunction) {

        AwsRequestOverrideConfiguration overrideConfiguration = AwsRequestOverrideConfiguration.builder()
            .credentialsProvider(v2CredentialsProvider).build();

        @SuppressWarnings("unchecked")
        RequestT wrappedRequest = (RequestT) request.toBuilder().overrideConfiguration(overrideConfiguration).build();

        try {
            CompletableFuture<ResultT> response = requestFunction.apply(wrappedRequest).thenApplyAsync(resultT -> {
                logRequestMetadataV2(request, resultT);
                return resultT;
            });
            return response;
        } catch (final Throwable e) {
            loggerProxy.log(String.format("Failed to execute remote function: {%s}", e.getMessage()));
            throw e;
        }
    }

    public <RequestT extends AwsRequest, ResultT extends AwsResponse, IterableT extends SdkIterable<ResultT>>
        IterableT
        injectCredentialsAndInvokeIterableV2(final RequestT request, final Function<RequestT, IterableT> requestFunction) {

        AwsRequestOverrideConfiguration overrideConfiguration = AwsRequestOverrideConfiguration.builder()
            .credentialsProvider(v2CredentialsProvider).build();

        @SuppressWarnings("unchecked")
        RequestT wrappedRequest = (RequestT) request.toBuilder().overrideConfiguration(overrideConfiguration).build();

        try {
            IterableT response = requestFunction.apply(wrappedRequest);
            response.forEach(r -> logRequestMetadataV2(request, r));
            return response;
        } catch (final Throwable e) {
            loggerProxy.log(String.format("Failed to execute remote function: {%s}", e.getMessage()));
            throw e;
        }
    }

    public <RequestT extends AwsRequest, ResultT extends AwsResponse>
        ResponseInputStream<ResultT>
        injectCredentialsAndInvokeV2InputStream(final RequestT request,
                                                final Function<RequestT, ResponseInputStream<ResultT>> requestFunction) {

        AwsRequestOverrideConfiguration overrideConfiguration = AwsRequestOverrideConfiguration.builder()
            .credentialsProvider(v2CredentialsProvider).build();

        @SuppressWarnings("unchecked")
        RequestT wrappedRequest = (RequestT) request.toBuilder().overrideConfiguration(overrideConfiguration).build();

        try {
            ResponseInputStream<ResultT> response = requestFunction.apply(wrappedRequest);
            logRequestMetadataV2(request, response.response());
            return response;
        } catch (final Throwable e) {
            loggerProxy.log(String.format("Failed to execute remote function: {%s}", e.getMessage()));
            throw e;
        }
    }

    public <RequestT extends AwsRequest, ResultT extends AwsResponse>
        ResponseBytes<ResultT>
        injectCredentialsAndInvokeV2Bytes(final RequestT request,
                                          final Function<RequestT, ResponseBytes<ResultT>> requestFunction) {

        AwsRequestOverrideConfiguration overrideConfiguration = AwsRequestOverrideConfiguration.builder()
            .credentialsProvider(v2CredentialsProvider).build();

        @SuppressWarnings("unchecked")
        RequestT wrappedRequest = (RequestT) request.toBuilder().overrideConfiguration(overrideConfiguration).build();

        try {
            ResponseBytes<ResultT> response = requestFunction.apply(wrappedRequest);
            logRequestMetadataV2(request, response.response());
            return response;
        } catch (final Throwable e) {
            loggerProxy.log(String.format("Failed to execute remote function: {%s}", e.getMessage()));
            throw e;
        }
    }

    public <RequestT, ClientT, ModelT, CallbackT extends StdCallbackContext>
        ProgressEvent<ModelT, CallbackT>
        defaultHandler(RequestT request, Exception e, ClientT client, ModelT model, CallbackT context) throws Exception {
        //
        // Client side exception, mapping this to InvalidRequest at the moment
        //
        if (e instanceof NonRetryableException) {
            return ProgressEvent.failed(model, context, HandlerErrorCode.InvalidRequest, e.getMessage());
        }

        if (e instanceof AwsServiceException) {
            AwsServiceException sdkException = (AwsServiceException) e;
            AwsErrorDetails details = sdkException.awsErrorDetails();
            String errMsg = sdkException.getMessage();
            switch (details.sdkHttpResponse().statusCode()) {
                case HttpStatusCode.BAD_REQUEST:
                    //
                    // BadRequest, wrong values in the request
                    //
                    return ProgressEvent.failed(model, context, HandlerErrorCode.InvalidRequest, errMsg);

                case HttpStatusCode.UNAUTHORIZED:
                case HttpStatusCode.FORBIDDEN:
                case HTTP_STATUS_NETWORK_AUTHN_REQUIRED: // 511 Network Authentication Required, just in case
                    //
                    // Access Denied, AuthN/Z problems
                    //
                    return ProgressEvent.failed(model, context, HandlerErrorCode.AccessDenied, errMsg);

                case HttpStatusCode.NOT_FOUND:
                case HTTP_STATUS_GONE: // 410 Gone
                    //
                    // Resource that we are trying READ/UPDATE/DELETE is not found
                    //
                    return ProgressEvent.failed(model, context, HandlerErrorCode.NotFound, errMsg);

                case HttpStatusCode.SERVICE_UNAVAILABLE:
                    //
                    // Often retries help here as well. IMPORTANT to remember here that
                    // there are retries with the SDK Client itself for these. Verify
                    // what we add extra over the default ones
                    //
                case HttpStatusCode.GATEWAY_TIMEOUT:
                case HttpStatusCode.THROTTLING: // Throttle, TOO many requests
                    AmazonWebServicesClientProxy.this.loggerProxy.log("Retrying for error " + details.errorMessage());
                    throw RetryableException.builder().cause(e).build();

                default:
                    return ProgressEvent.failed(model, context, HandlerErrorCode.GeneralServiceException, errMsg);
            }
        }
        return ProgressEvent.failed(model, context, HandlerErrorCode.InternalFailure, e.getMessage());

    }

    private <RequestT extends AmazonWebServiceRequest, ResultT extends AmazonWebServiceResult<ResponseMetadata>>
        void
        logRequestMetadata(final RequestT request, final ResultT response) {
        try {
            String requestName = request.getClass().getSimpleName();
            String requestId = (response == null || response.getSdkResponseMetadata() == null)
                ? ""
                : response.getSdkResponseMetadata().getRequestId();
            loggerProxy
                .log(String.format("{\"apiRequest\": {\"requestId\": \"%s\", \"requestName\": \"%s\"}}", requestId, requestName));
        } catch (final Exception e) {
            loggerProxy.log(e.getMessage());
        }
    }

    private <RequestT extends AwsRequest, ResultT extends AwsResponse> void logRequestMetadataV2(final RequestT request,
                                                                                                 final ResultT response) {
        try {
            String requestName = request.getClass().getSimpleName();
            String requestId = (response == null || response.responseMetadata() == null)
                ? ""
                : response.responseMetadata().requestId();
            loggerProxy
                .log(String.format("{\"apiRequest\": {\"requestId\": \"%s\", \"requestName\": \"%s\"}}", requestId, requestName));
        } catch (final Exception e) {
            loggerProxy.log(e.getMessage());
        }
    }
}
