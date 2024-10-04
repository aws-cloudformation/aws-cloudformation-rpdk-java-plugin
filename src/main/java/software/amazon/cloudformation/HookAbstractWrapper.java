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
package software.amazon.cloudformation;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.retry.RetryUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.http.HttpStatusFamily;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.cloudformation.encryption.Cipher;
import software.amazon.cloudformation.encryption.KMSCipher;
import software.amazon.cloudformation.exceptions.BaseHandlerException;
import software.amazon.cloudformation.exceptions.EncryptionException;
import software.amazon.cloudformation.exceptions.FileScrubberException;
import software.amazon.cloudformation.exceptions.TerminalException;
import software.amazon.cloudformation.injection.CloudWatchLogsProvider;
import software.amazon.cloudformation.injection.CloudWatchProvider;
import software.amazon.cloudformation.injection.CredentialsProvider;
import software.amazon.cloudformation.injection.SessionCredentialsProvider;
import software.amazon.cloudformation.loggers.CloudWatchLogHelper;
import software.amazon.cloudformation.loggers.CloudWatchLogPublisher;
import software.amazon.cloudformation.loggers.LogPublisher;
import software.amazon.cloudformation.metrics.HookMetricsPublisherImpl;
import software.amazon.cloudformation.metrics.MetricsPublisher;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Credentials;
import software.amazon.cloudformation.proxy.DelayFactory;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.LoggerProxy;
import software.amazon.cloudformation.proxy.MetricsPublisherProxy;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.WaitStrategy;
import software.amazon.cloudformation.proxy.hook.HookHandlerRequest;
import software.amazon.cloudformation.proxy.hook.HookInvocationRequest;
import software.amazon.cloudformation.proxy.hook.HookProgressEvent;
import software.amazon.cloudformation.proxy.hook.HookRequestContext;
import software.amazon.cloudformation.proxy.hook.HookStatus;
import software.amazon.cloudformation.resource.SchemaValidator;
import software.amazon.cloudformation.resource.Serializer;
import software.amazon.cloudformation.resource.Validator;

public abstract class HookAbstractWrapper<TargetT, CallbackT, ConfigurationT> {

    public static final SdkHttpClient HTTP_CLIENT = ApacheHttpClient.builder().build();
    private static final Logger LOG = LoggerFactory.getLogger(HookAbstractWrapper.class);

    protected final Serializer serializer;
    protected LoggerProxy loggerProxy;
    protected MetricsPublisherProxy metricsPublisherProxy;
    protected Cipher cipher;

    protected LoggerProxy platformLoggerProxy;
    protected LogPublisher platformLogPublisher;

    // provider... prefix indicates credential provided by hook owner

    final CredentialsProvider providerCredentialsProvider;
    final CloudWatchProvider providerCloudWatchProvider;
    final CloudWatchLogsProvider cloudWatchLogsProvider;
    final SchemaValidator validator;
    final TypeReference<HookInvocationRequest<ConfigurationT, CallbackT>> typeReference;

    private MetricsPublisher providerMetricsPublisher;

    private CloudWatchLogHelper cloudWatchLogHelper;
    private CloudWatchLogPublisher providerEventsLogger;

    protected HookAbstractWrapper() {
        this.providerCredentialsProvider = new SessionCredentialsProvider();
        this.providerCloudWatchProvider = new CloudWatchProvider(this.providerCredentialsProvider, HTTP_CLIENT);
        this.cloudWatchLogsProvider = new CloudWatchLogsProvider(this.providerCredentialsProvider, HTTP_CLIENT);
        this.serializer = new Serializer();
        this.validator = new Validator();
        this.typeReference = getTypeReference();
        this.platformLoggerProxy = new LoggerProxy();
    }

    /*
     * This .ctor provided for testing
     */
    public HookAbstractWrapper(final CredentialsProvider providerCredentialsProvider,
                               final CloudWatchLogPublisher providerEventsLogger,
                               final LogPublisher platformEventsLogger,
                               final MetricsPublisher providerMetricsPublisher,
                               final SchemaValidator validator,
                               final Serializer serializer,
                               final SdkHttpClient httpClient,
                               final Cipher cipher) {
        this.providerCredentialsProvider = providerCredentialsProvider;
        this.providerCloudWatchProvider = new CloudWatchProvider(this.providerCredentialsProvider, httpClient);
        this.cloudWatchLogsProvider = new CloudWatchLogsProvider(this.providerCredentialsProvider, httpClient);
        this.providerEventsLogger = providerEventsLogger;
        this.platformLogPublisher = platformEventsLogger;
        this.providerMetricsPublisher = providerMetricsPublisher;
        this.serializer = serializer;
        this.validator = validator;
        this.typeReference = getTypeReference();
        this.cipher = cipher;
        this.platformLoggerProxy = new LoggerProxy();
    }

    /**
     * This function initialises dependencies which are depending on credentials
     * passed at function invoke and not available during construction
     */
    private void initialiseRuntime(final String hookTypeName,
                                   final String providerCredentials,
                                   final String providerLogGroupName,
                                   final String awsAccountId,
                                   final String hookEncryptionKeyArn,
                                   final String hookEncryptionKeyRole) {

        this.metricsPublisherProxy = new MetricsPublisherProxy();
        this.loggerProxy = new LoggerProxy();
        this.loggerProxy.addLogPublisher(this.platformLogPublisher);

        // Initialisation skipped if dependencies were set during injection (in unit
        // tests).

        // Initialize a KMS cipher to decrypt customer credentials in HookRequestData
        if (this.cipher == null && hookEncryptionKeyArn != null && hookEncryptionKeyRole != null) {
            this.cipher = new KMSCipher(hookEncryptionKeyArn, hookEncryptionKeyRole);
        }

        // NOTE: providerCredentials and providerLogGroupName are null/not null in
        // sync.
        // Both are required parameters when LoggingConfig (optional) is provided when
        // 'RegisterType'.
        final Credentials processedProviderCredentials = processCredentials(providerCredentials);
        if (processedProviderCredentials != null) {
            if (this.providerCredentialsProvider != null) {
                this.providerCredentialsProvider.setCredentials(processedProviderCredentials);
            }

            if (this.providerMetricsPublisher == null) {
                this.providerMetricsPublisher = new HookMetricsPublisherImpl(this.providerCloudWatchProvider, this.loggerProxy,
                                                                             awsAccountId, hookTypeName);
            }
            this.metricsPublisherProxy.addMetricsPublisher(this.providerMetricsPublisher);
            this.providerMetricsPublisher.refreshClient();

            if (this.providerEventsLogger == null) {
                this.cloudWatchLogHelper = new CloudWatchLogHelper(this.cloudWatchLogsProvider, providerLogGroupName,
                                                                   this.platformLoggerProxy, this.metricsPublisherProxy);
                this.cloudWatchLogHelper.refreshClient();

                this.providerEventsLogger = new CloudWatchLogPublisher(this.cloudWatchLogsProvider, providerLogGroupName,
                                                                       this.cloudWatchLogHelper.prepareLogStream(),
                                                                       this.platformLoggerProxy, this.metricsPublisherProxy);
            }
            this.loggerProxy.addLogPublisher(this.providerEventsLogger);
            this.providerEventsLogger.refreshClient();
        }
    }

    public void processRequest(final InputStream inputStream, final OutputStream outputStream) throws IOException,
        TerminalException {

        ProgressEvent<TargetT, CallbackT> handlerResponse = null;
        HookInvocationRequest<ConfigurationT, CallbackT> request = null;
        scrubFiles();
        try {
            if (inputStream == null) {
                throw new TerminalException("No request object received");
            }

            String input = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
            JSONObject rawInput = new JSONObject(new JSONTokener(input));
            // deserialize incoming payload to modeled request
            request = this.serializer.deserialize(input, typeReference);
            handlerResponse = processInvocation(rawInput, request);
        } catch (final Throwable e) {
            // Exceptions are wrapped as a consistent error response to the caller (i.e;
            // CloudFormation)
            logError(ExceptionUtils.getStackTrace(e));

            handlerResponse = ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.InternalFailure);
            publishExceptionMetric(request != null ? request.getActionInvocationPoint() : null, e,
                HandlerErrorCode.InternalFailure);
        } finally {
            // A response will be output on all paths, though CloudFormation will
            // not block on invoking the handlers, but rather listen for callbacks
            writeResponse(outputStream, createProgressResponse(handlerResponse, request));
            publishExceptionCodeAndCountMetrics(request == null ? null : request.getActionInvocationPoint(),
                handlerResponse.getErrorCode());
        }
    }

    private ProgressEvent<TargetT, CallbackT> processInvocation(final JSONObject rawRequest,
                                                                final HookInvocationRequest<ConfigurationT, CallbackT> request)
        throws IOException,
        TerminalException {

        assert request != null : "Invalid request object received. Request object is null";

        if (request.getRequestData() == null || request.getRequestData().getTargetModel() == null) {
            throw new TerminalException("Invalid request object received. Target Model can not be null.");
        }

        // TODO: Include hook schema validation here after schema is finalized

        try {
            // initialise dependencies with platform credentials
            initialiseRuntime(request.getHookTypeName(), request.getRequestData().getProviderCredentials(),
                request.getRequestData().getProviderLogGroupName(), request.getAwsAccountId(),
                request.getRequestData().getHookEncryptionKeyArn(), request.getRequestData().getHookEncryptionKeyRole());

            // transform the request object to pass to caller
            HookHandlerRequest hookHandlerRequest = transform(request);
            ConfigurationT typeConfiguration = request.getHookModel();

            HookRequestContext<CallbackT> requestContext = request.getRequestContext();

            this.metricsPublisherProxy.publishInvocationMetric(Instant.now(), request.getActionInvocationPoint());

            // last mile proxy creation with passed-in credentials (unless we are operating
            // in a non-AWS model)
            AmazonWebServicesClientProxy awsClientProxy = null;
            Credentials processedCallerCredentials = processCredentials(request.getRequestData().getCallerCredentials());
            if (processedCallerCredentials != null) {
                awsClientProxy = new AmazonWebServicesClientProxy(this.loggerProxy, processedCallerCredentials,
                                                                  DelayFactory.CONSTANT_DEFAULT_DELAY_FACTORY,
                                                                  WaitStrategy.scheduleForCallbackStrategy(), true);

            }

            CallbackT callbackContext = (requestContext != null) ? requestContext.getCallbackContext() : null;

            return wrapInvocationAndHandleErrors(awsClientProxy, hookHandlerRequest, request, callbackContext, typeConfiguration);

        } catch (EncryptionException e) {
            publishExceptionMetric(request.getActionInvocationPoint(), e, HandlerErrorCode.AccessDenied);
            logUnhandledError("An encryption error occurred while processing request", request, e);

            return ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.AccessDenied);
        }
    }

    private void logUnhandledError(final String errorDescription,
                                   final HookInvocationRequest<ConfigurationT, CallbackT> request,
                                   final Throwable e) {
        logError(String.format("%s in a %s action on a %s: %s%n%s", errorDescription, request.getActionInvocationPoint(),
            request.getHookTypeName(), e.toString(), ExceptionUtils.getStackTrace(e)));
    }

    /**
     * Invokes the handler implementation for the request, and wraps with try-catch
     * to consistently handle certain classes of errors and correctly map those to
     * the appropriate HandlerErrorCode Also wraps the invocation in last-mile
     * timing metrics
     */
    private ProgressEvent<TargetT, CallbackT>
        wrapInvocationAndHandleErrors(final AmazonWebServicesClientProxy awsClientProxy,
                                      final HookHandlerRequest hookHandlerRequest,
                                      final HookInvocationRequest<ConfigurationT, CallbackT> request,
                                      final CallbackT callbackContext,
                                      final ConfigurationT typeConfiguration) {

        Date startTime = Date.from(Instant.now());
        try {
            ProgressEvent<TargetT, CallbackT> handlerResponse = invokeHandler(awsClientProxy, hookHandlerRequest,
                request.getActionInvocationPoint(), callbackContext, typeConfiguration);
            if (handlerResponse != null) {
                this.log(String.format("Handler returned %s", handlerResponse.getStatus()));
            } else {
                this.logError("Handler returned null");
                throw new TerminalException("Handler failed to provide a response.");
            }

            return handlerResponse;
        } catch (final BaseHandlerException e) {
            publishExceptionMetric(request.getActionInvocationPoint(), e, e.getErrorCode());
            logUnhandledError(e.getMessage(), request, e);

            return ProgressEvent.defaultFailureHandler(e, e.getErrorCode());
        } catch (final AmazonServiceException | AwsServiceException e) {
            if ((e instanceof AwsServiceException && ((AwsServiceException) e).statusCode() == HttpStatusCode.BAD_REQUEST)
                || (e instanceof AmazonServiceException
                    && ((AmazonServiceException) e).getStatusCode() == HttpStatusCode.BAD_REQUEST)) {
                this.log(String.format("%s [%s] call with invalid request to downstream service", request.getHookTypeName(),
                    request.getActionInvocationPoint()));
                publishExceptionMetric(request.getActionInvocationPoint(), e, HandlerErrorCode.InvalidRequest);

                return ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.InvalidRequest);
            } else if ((e instanceof AwsServiceException && ((AwsServiceException) e).isThrottlingException())
                || (e instanceof AmazonServiceException && RetryUtils.isThrottlingException((AmazonServiceException) e))) {
                this.log(String.format("%s [%s] call throttled by downstream service", request.getHookTypeName(),
                    request.getActionInvocationPoint()));
                publishExceptionMetric(request.getActionInvocationPoint(), e, HandlerErrorCode.Throttling);

                return ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.Throttling);
            } else if ((e instanceof AwsServiceException
                && HttpStatusFamily.of(((AwsServiceException) e).statusCode()) == HttpStatusFamily.SERVER_ERROR)
                || (e instanceof AmazonServiceException
                    && HttpStatusFamily.of(((AmazonServiceException) e).getStatusCode()) == HttpStatusFamily.SERVER_ERROR)) {
                publishExceptionMetric(request.getActionInvocationPoint(), e, HandlerErrorCode.ServiceInternalError);
                logUnhandledError("An unknown downstream service error occurred", request, e);

                return ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.ServiceInternalError);
            } else {
                publishExceptionMetric(request.getActionInvocationPoint(), e, HandlerErrorCode.GeneralServiceException);
                logUnhandledError("A downstream service error occurred", request, e);

                return ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.GeneralServiceException);
            }
        } catch (final Throwable e) {
            publishExceptionMetric(request.getActionInvocationPoint(), e, HandlerErrorCode.InternalFailure);
            logUnhandledError("An unknown error occurred ", request, e);

            return ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.InternalFailure);
        } finally {
            Date endTime = Date.from(Instant.now());
            metricsPublisherProxy.publishDurationMetric(Instant.now(), request.getActionInvocationPoint(),
                (endTime.getTime() - startTime.getTime()));
        }
    }

    private HookProgressEvent<CallbackT> createProgressResponse(final ProgressEvent<TargetT, CallbackT> progressEvent,
                                                                final HookInvocationRequest<ConfigurationT, CallbackT> request) {
        final HookProgressEvent<CallbackT> response = new HookProgressEvent<>();
        response.setHookStatus(getHookStatus(progressEvent.getStatus()));
        response.setErrorCode(progressEvent.getErrorCode());
        response.setMessage(progressEvent.getMessage());
        response.setResult(progressEvent.getResult());
        response.setCallbackContext(progressEvent.getCallbackContext());
        response.setCallbackDelaySeconds(progressEvent.getCallbackDelaySeconds());
        if (request != null) {
            response.setClientRequestToken(request.getClientRequestToken());
        }

        return response;
    }

    private void writeResponse(final OutputStream outputStream, final HookProgressEvent<CallbackT> response) throws IOException {
        String output = this.serializer.serialize(response);
        outputStream.write(output.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /**
     * Transforms the incoming request to the subset of typed models which the
     * handler implementor needs
     *
     * @param request The request as passed from the caller (e.g; CloudFormation)
     *            which contains additional context to inform the LambdaWrapper
     *            itself, and is not needed by the handler implementations
     * @return A converted HookHandlerRequest model
     */
    protected abstract HookHandlerRequest transform(HookInvocationRequest<ConfigurationT, CallbackT> request) throws IOException;

    /**
     * Handler implementation should implement this method to provide the schema for
     * validation
     *
     * @return An JSONObject of the hook schema for the provider
     */
    protected abstract JSONObject provideHookSchemaJSONObject();

    /**
     * Implemented by the handler package as the key entry point.
     *
     * @param proxy Amazon webservice proxy to inject credentials correctly.
     * @param request incoming request for the call
     * @param invocationPoint which invocation point the hook is invoked at
     * @param callbackContext the callback context to handle reentrant calls
     * @return progress event indicating complete, in progress with delay callback
     *         or failed state
     * @throws Exception propagate any unexpected errors
     */
    public abstract ProgressEvent<TargetT, CallbackT> invokeHandler(AmazonWebServicesClientProxy proxy,
                                                                    HookHandlerRequest request,
                                                                    HookInvocationPoint invocationPoint,
                                                                    CallbackT callbackContext,
                                                                    ConfigurationT typeConfiguration)
        throws Exception;

    /*
     * null-safe exception metrics delivery
     */
    private void publishExceptionMetric(final HookInvocationPoint invocationPoint,
                                        final Throwable ex,
                                        final HandlerErrorCode handlerErrorCode) {
        if (this.metricsPublisherProxy != null) {
            this.metricsPublisherProxy.publishExceptionMetric(Instant.now(), invocationPoint, ex, handlerErrorCode);
        } else if (platformLoggerProxy != null) {
            platformLoggerProxy.log(ex.toString());
        } else {
            LOG.error(ex.toString());
        }
    }

    /*
     * null-safe exception metrics delivery
     */
    private void publishExceptionCodeAndCountMetrics(final HookInvocationPoint invocationPoint,
                                                     final HandlerErrorCode handlerErrorCode) {
        if (this.metricsPublisherProxy != null) {
            this.metricsPublisherProxy.publishExceptionByErrorCodeAndCountBulkMetrics(Instant.now(), invocationPoint,
                handlerErrorCode);
        }
    }

    private Credentials processCredentials(final String rawCredentials) {
        if (rawCredentials == null) {
            return null;
        }

        if (this.cipher != null) {
            return this.cipher.decryptCredentials(rawCredentials);
        }

        // Attempt to deserialize credentials if they are not encrypted.
        // This would happen while running local contract tests
        try {
            return this.serializer.deserialize(rawCredentials, new TypeReference<Credentials>() {
            });
        } catch (IOException e) {
            return null;
        }
    }

    private HookStatus getHookStatus(final OperationStatus status) {
        if (status == OperationStatus.PENDING) {
            return HookStatus.PENDING;
        } else if (status == OperationStatus.IN_PROGRESS) {
            return HookStatus.IN_PROGRESS;
        } else if (status == OperationStatus.SUCCESS) {
            return HookStatus.SUCCESS;
        } else {
            return HookStatus.FAILED;
        }
    }

    /**
     * null-safe logger redirect
     *
     * @param message A string containing the event to log.
     */
    private void log(final String message) {
        if (this.loggerProxy != null) {
            this.loggerProxy.log(String.format("%s%n", message));
        } else if (platformLoggerProxy != null) {
            platformLoggerProxy.log(message);
        } else {
            LOG.info(message);
        }
    }

    private void logError(final String message) {
        if (this.loggerProxy != null) {
            this.loggerProxy.log(String.format("%s%n", message));
        } else if (platformLoggerProxy != null) {
            platformLoggerProxy.log(message);
        } else {
            LOG.error(message);
        }
    }

    protected abstract TypeReference<HookInvocationRequest<ConfigurationT, CallbackT>> getTypeReference();

    protected abstract TypeReference<ConfigurationT> getModelTypeReference();

    protected void scrubFiles() {
        try {
            FileUtils.cleanDirectory(FileUtils.getTempDirectory());
        } catch (IOException e) {
            log(e.getMessage());
            publishExceptionMetric(null, new FileScrubberException(e), HandlerErrorCode.InternalFailure);
        }
    }
}
