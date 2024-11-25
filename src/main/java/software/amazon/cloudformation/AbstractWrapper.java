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
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONObject;
import org.json.JSONTokener;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.cloudformation.exceptions.BaseHandlerException;
import software.amazon.cloudformation.exceptions.CfnInvalidRequestException;
import software.amazon.cloudformation.exceptions.FileScrubberException;
import software.amazon.cloudformation.exceptions.TerminalException;
import software.amazon.cloudformation.injection.CloudWatchLogsProvider;
import software.amazon.cloudformation.injection.CloudWatchProvider;
import software.amazon.cloudformation.injection.CredentialsProvider;
import software.amazon.cloudformation.injection.SessionCredentialsProvider;
import software.amazon.cloudformation.loggers.CloudWatchLogHelper;
import software.amazon.cloudformation.loggers.CloudWatchLogPublisher;
import software.amazon.cloudformation.loggers.LogPublisher;
import software.amazon.cloudformation.metrics.MetricsPublisher;
import software.amazon.cloudformation.metrics.MetricsPublisherImpl;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Credentials;
import software.amazon.cloudformation.proxy.DelayFactory;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.HandlerRequest;
import software.amazon.cloudformation.proxy.LoggerProxy;
import software.amazon.cloudformation.proxy.MetricsPublisherProxy;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import software.amazon.cloudformation.proxy.WaitStrategy;
import software.amazon.cloudformation.resource.ResourceTypeSchema;
import software.amazon.cloudformation.resource.SchemaValidator;
import software.amazon.cloudformation.resource.Serializer;
import software.amazon.cloudformation.resource.Validator;
import software.amazon.cloudformation.resource.exceptions.ValidationException;

public abstract class AbstractWrapper<ResourceT, CallbackT, ConfigurationT> {

    public static final SdkHttpClient HTTP_CLIENT = ApacheHttpClient.builder().build();

    private static final Set<Action> MUTATING_ACTIONS = ImmutableSet.of(Action.CREATE, Action.DELETE, Action.UPDATE);
    private static final Set<Action> VALIDATING_ACTIONS = ImmutableSet.of(Action.CREATE, Action.UPDATE);

    protected final Serializer serializer;
    protected LoggerProxy loggerProxy;
    protected MetricsPublisherProxy metricsPublisherProxy;

    protected LoggerProxy platformLoggerProxy;
    protected LogPublisher platformLogPublisher;

    // provider... prefix indicates credential provided by resource owner
    protected final CredentialsProvider providerCredentialsProvider;

    protected final CloudWatchProvider providerCloudWatchProvider;
    protected final CloudWatchLogsProvider cloudWatchLogsProvider;
    protected final SchemaValidator validator;
    protected final TypeReference<HandlerRequest<ResourceT, CallbackT, ConfigurationT>> typeReference;

    protected MetricsPublisher providerMetricsPublisher;

    protected CloudWatchLogHelper cloudWatchLogHelper;
    protected CloudWatchLogPublisher providerEventsLogger;

    protected AbstractWrapper() {
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
    public AbstractWrapper(final CredentialsProvider providerCredentialsProvider,
                           final LogPublisher platformEventsLogger,
                           final CloudWatchLogPublisher providerEventsLogger,
                           final MetricsPublisher providerMetricsPublisher,
                           final SchemaValidator validator,
                           final Serializer serializer,
                           final SdkHttpClient httpClient) {

        this.providerCredentialsProvider = providerCredentialsProvider;
        this.providerCloudWatchProvider = new CloudWatchProvider(this.providerCredentialsProvider, httpClient);
        this.cloudWatchLogsProvider = new CloudWatchLogsProvider(this.providerCredentialsProvider, httpClient);
        this.platformLogPublisher = platformEventsLogger;
        this.providerEventsLogger = providerEventsLogger;
        this.providerMetricsPublisher = providerMetricsPublisher;
        this.serializer = serializer;
        this.validator = validator;
        this.typeReference = getTypeReference();
        this.platformLoggerProxy = new LoggerProxy();

    }

    /**
     * This function initialises dependencies which are depending on credentials
     * passed at function invoke and not available during construction
     */
    private void
        initialiseRuntime(final String resourceType, final Credentials providerCredentials, final String providerLogGroupName) {

        this.metricsPublisherProxy = new MetricsPublisherProxy();
        this.loggerProxy = new LoggerProxy();
        this.loggerProxy.addLogPublisher(this.platformLogPublisher);

        // Initialisation skipped if dependencies were set during injection (in unit
        // tests).

        // NOTE: providerCredentials and providerLogGroupName are null/not null in
        // sync.
        // Both are required parameters when LoggingConfig (optional) is provided when
        // 'RegisterType'.
        // reset provider credentials back to null to avoid reusing stale credentials
        this.providerCredentialsProvider.resetCredentials();
        this.providerMetricsPublisher = null;
        this.providerEventsLogger = null;
        this.cloudWatchLogHelper = null;

        if (providerCredentials != null) {
            if (this.providerCredentialsProvider != null) {
                this.providerCredentialsProvider.setCredentials(providerCredentials);
            }

            if (this.providerMetricsPublisher == null) {
                this.providerMetricsPublisher = new MetricsPublisherImpl(this.providerCloudWatchProvider, this.loggerProxy,
                                                                         resourceType);
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

        ProgressEvent<ResourceT, CallbackT> handlerResponse = null;
        HandlerRequest<ResourceT, CallbackT, ConfigurationT> request = null;
        scrubFiles();
        try {
            if (inputStream == null) {
                throw new TerminalException("No request object received");
            }

            String input = this.serializer.decompress(IOUtils.toString(inputStream, StandardCharsets.UTF_8));

            JSONObject rawInput = new JSONObject(new JSONTokener(input));
            // deserialize incoming payload to modelled request
            try {
                request = this.serializer.deserialize(input, typeReference);

                handlerResponse = processInvocation(rawInput, request);
            } catch (MismatchedInputException e) {
                JSONObject resourceSchemaJSONObject = provideResourceSchemaJSONObject();
                JSONObject rawModelObject = rawInput.getJSONObject("requestData").getJSONObject("resourceProperties");

                this.validator.validateObject(rawModelObject, resourceSchemaJSONObject);

                handlerResponse = ProgressEvent.defaultFailureHandler(
                    new CfnInvalidRequestException("Resource properties validation failed with invalid configuration", e),
                    HandlerErrorCode.InvalidRequest);

            }

        } catch (final ValidationException e) {
            String message;
            String fullExceptionMessage = ValidationException.buildFullExceptionMessage(e);
            if (!StringUtils.isEmpty(fullExceptionMessage)) {
                message = String.format("Model validation failed (%s)", fullExceptionMessage);
            } else {
                message = "Model validation failed with unknown cause.";
            }

            publishExceptionMetric(request == null ? null : request.getAction(), e, HandlerErrorCode.InvalidRequest);
            handlerResponse = ProgressEvent.defaultFailureHandler(new TerminalException(message, e),
                HandlerErrorCode.InvalidRequest);
        } catch (final Throwable e) {
            // Exceptions are wrapped as a consistent error response to the caller (i.e;
            // CloudFormation)
            log(ExceptionUtils.getStackTrace(e)); // for root causing
            handlerResponse = ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.InternalFailure);
            if (request != null && request.getRequestData() != null && MUTATING_ACTIONS.contains(request.getAction())) {
                handlerResponse.setResourceModel(request.getRequestData().getResourceProperties());
            }
            if (request != null) {
                publishExceptionMetric(request.getAction(), e, HandlerErrorCode.InternalFailure);
            }

        } finally {
            // A response will be output on all paths, though CloudFormation will
            // not block on invoking the handlers, but rather listen for callbacks
            writeResponse(outputStream, handlerResponse);
            publishExceptionCodeAndCountMetrics(request == null ? null : request.getAction(), handlerResponse.getErrorCode());
        }
    }

    private ProgressEvent<ResourceT, CallbackT>
        processInvocation(final JSONObject rawRequest, final HandlerRequest<ResourceT, CallbackT, ConfigurationT> request)
            throws IOException,
            TerminalException {
        assert request != null : "Invalid request object received";

        if (request.getRequestData() == null) {
            throw new TerminalException("Invalid request object received");
        }

        final boolean isMutatingAction = MUTATING_ACTIONS.contains(request.getAction());
        if (isMutatingAction) {
            if (request.getRequestData().getResourceProperties() == null) {
                throw new TerminalException("Invalid resource properties object received");
            }
        }

        // initialise dependencies
        initialiseRuntime(request.getResourceType(), request.getRequestData().getProviderCredentials(),
            request.getRequestData().getProviderLogGroupName());

        // transform the request object to pass to caller
        ResourceHandlerRequest<ResourceT> resourceHandlerRequest = transform(request);
        ConfigurationT typeConfiguration = request.getRequestData().getTypeConfiguration();

        if (resourceHandlerRequest != null) {
            resourceHandlerRequest.setPreviousResourceTags(getPreviousResourceTags(request));
            resourceHandlerRequest.setStackId(getStackId(request));
            resourceHandlerRequest.setSnapshotRequested(request.getSnapshotRequested());
            resourceHandlerRequest.setRollback(request.getRollback());
            resourceHandlerRequest.setDriftable(request.getDriftable());
            resourceHandlerRequest.setFeatures(request.getFeatures());
            resourceHandlerRequest.setUpdatePolicy(request.getUpdatePolicy());
            resourceHandlerRequest.setCreationPolicy(request.getCreationPolicy());
            if (request.getRequestData() != null) {
                resourceHandlerRequest.setPreviousSystemTags(request.getRequestData().getPreviousSystemTags());
            }
        }

        this.metricsPublisherProxy.publishInvocationMetric(Instant.now(), request.getAction());

        // for create and update actions, validate incoming model - any error is a
        // terminal failure on
        // the invocation
        // NOTE: we validate the raw pre-deserialized payload to account for lenient
        // serialization.
        // NOTE: Validation is not required on deletion as only the primary identifier
        // is required to delete.
        // Here, we want to surface ALL input validation errors to the caller.
        final boolean shouldValidate = VALIDATING_ACTIONS.contains(request.getAction()) && request.getCallbackContext() == null;
        if (shouldValidate) {
            // validate entire incoming payload, including extraneous fields which
            // are stripped by the Serializer (due to FAIL_ON_UNKNOWN_PROPERTIES setting)
            JSONObject rawModelObject = rawRequest.getJSONObject("requestData").getJSONObject("resourceProperties");
            try {
                validateModel(rawModelObject);
            } catch (final ValidationException e) {
                // TODO: we'll need a better way to expose the stack of causing exceptions for
                // user feedback
                StringBuilder validationMessageBuilder = new StringBuilder();
                if (!StringUtils.isEmpty(e.getMessage())) {
                    validationMessageBuilder.append(String.format("Model validation failed (%s)", e.getMessage()));
                } else {
                    validationMessageBuilder.append("Model validation failed with unknown cause.");
                }
                List<ValidationException> es = e.getCausingExceptions();
                if (CollectionUtils.isNotEmpty(es)) {
                    for (RuntimeException cause : es) {
                        if (cause instanceof ValidationException) {
                            validationMessageBuilder.append(
                                String.format("%n%s (%s)", cause.getMessage(), ((ValidationException) cause).getSchemaPointer()));
                        }
                    }
                }
                publishExceptionMetric(request.getAction(), e, HandlerErrorCode.InvalidRequest);
                return ProgressEvent.defaultFailureHandler(new TerminalException(validationMessageBuilder.toString(), e),
                    HandlerErrorCode.InvalidRequest);
            }
        }

        CallbackT callbackContext = request.getCallbackContext();
        // last mile proxy creation with passed-in credentials (unless we are operating
        // in a non-AWS model)
        AmazonWebServicesClientProxy awsClientProxy = null;
        if (request.getRequestData().getCallerCredentials() != null) {
            awsClientProxy = new AmazonWebServicesClientProxy(this.loggerProxy, request.getRequestData().getCallerCredentials(),
                                                              DelayFactory.CONSTANT_DEFAULT_DELAY_FACTORY,
                                                              WaitStrategy.scheduleForCallbackStrategy());
        }

        ProgressEvent<ResourceT, CallbackT> handlerResponse = wrapInvocationAndHandleErrors(awsClientProxy,
            resourceHandlerRequest, request, callbackContext, typeConfiguration);

        if (handlerResponse.getStatus() == OperationStatus.IN_PROGRESS && !isMutatingAction) {
            throw new TerminalException("READ and LIST handlers must return synchronously.");
        }

        return handlerResponse;
    }

    private void logUnhandledError(final String errorDescription,
                                   final HandlerRequest<ResourceT, CallbackT, ConfigurationT> request,
                                   final Throwable e) {
        log(String.format("%s in a %s action on a %s: %s%n%s", errorDescription, request.getAction(), request.getResourceType(),
            e.toString(), ExceptionUtils.getStackTrace(e)));
    }

    /**
     * Invokes the handler implementation for the request, and wraps with try-catch
     * to consistently handle certain classes of errors and correctly map those to
     * the appropriate HandlerErrorCode Also wraps the invocation in last-mile
     * timing metrics
     */
    private ProgressEvent<ResourceT, CallbackT>
        wrapInvocationAndHandleErrors(final AmazonWebServicesClientProxy awsClientProxy,
                                      final ResourceHandlerRequest<ResourceT> resourceHandlerRequest,
                                      final HandlerRequest<ResourceT, CallbackT, ConfigurationT> request,
                                      final CallbackT callbackContext,
                                      final ConfigurationT typeConfiguration) {

        Date startTime = Date.from(Instant.now());
        try {
            ProgressEvent<ResourceT, CallbackT> handlerResponse = invokeHandler(awsClientProxy, resourceHandlerRequest,
                request.getAction(), callbackContext, typeConfiguration);
            if (handlerResponse != null) {
                this.log(String.format("Handler returned %s", handlerResponse.getStatus()));
            } else {
                this.log("Handler returned null");
                throw new TerminalException("Handler failed to provide a response.");
            }

            return handlerResponse;
        } catch (final BaseHandlerException e) {
            publishExceptionMetric(request.getAction(), e, e.getErrorCode());
            logUnhandledError(e.getMessage(), request, e);
            return ProgressEvent.defaultFailureHandler(e, e.getErrorCode());
        } catch (final AmazonServiceException | AwsServiceException e) {
            if ((e instanceof AwsServiceException && ((AwsServiceException) e).isThrottlingException())
                || (e instanceof AmazonServiceException && RetryUtils.isThrottlingException((AmazonServiceException) e))) {
                this.log(String.format("%s [%s] call throttled by downstream service", request.getResourceType(),
                    request.getAction()));
                publishExceptionMetric(request.getAction(), e, HandlerErrorCode.Throttling);
                return ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.Throttling);
            } else {
                publishExceptionMetric(request.getAction(), e, HandlerErrorCode.GeneralServiceException);
                logUnhandledError("A downstream service error occurred", request, e);
                return ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.GeneralServiceException);
            }
        } catch (final Throwable e) {
            publishExceptionMetric(request.getAction(), e, HandlerErrorCode.InternalFailure);
            logUnhandledError("An unknown error occurred ", request, e);
            return ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.InternalFailure);
        } finally {
            Date endTime = Date.from(Instant.now());
            metricsPublisherProxy.publishDurationMetric(Instant.now(), request.getAction(),
                (endTime.getTime() - startTime.getTime()));
        }

    }

    protected void writeResponse(final OutputStream outputStream, final ProgressEvent<ResourceT, CallbackT> response)
        throws IOException {
        if (response.getResourceModel() != null) {
            // strip write only properties on final results, we will need the intact model
            // while provisioning
            if (response.getStatus() != OperationStatus.IN_PROGRESS) {
                response.setResourceModel(sanitizeModel(response.getResourceModel()));
            }
        }

        if (response.getResult() != null) {
            // remove any non-resource specific fields from response
            response.setResult(null);
        }

        String output = this.serializer.serialize(response);
        outputStream.write(output.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    protected ResourceT sanitizeModel(final ResourceT model) throws IOException {
        // strip write only properties on final results, we will need the intact model
        // while provisioning
        final JSONObject modelObject = new JSONObject(this.serializer.serialize(model));
        ResourceTypeSchema.load(provideResourceSchemaJSONObject()).removeWriteOnlyProperties(modelObject);
        return this.serializer.deserializeStrict(modelObject.toString(), getModelTypeReference());
    }

    private void validateModel(final JSONObject modelObject) throws ValidationException, IOException {
        JSONObject resourceSchemaJSONObject = provideResourceSchemaJSONObject();
        if (resourceSchemaJSONObject == null) {
            throw new TerminalException("Unable to validate incoming model as no schema was provided.");
        }

        TypeReference<ResourceT> modelTypeReference = getModelTypeReference();

        // deserialize incoming payload to modelled request
        ResourceT deserializedModel;
        try {
            deserializedModel = this.serializer.deserializeStrict(modelObject.toString(), modelTypeReference);
        } catch (UnrecognizedPropertyException e) {
            throw new ValidationException(String.format("#: extraneous key [%s] is not permitted", e.getPropertyName()),
                                          "additionalProperties", "#");
        }

        JSONObject serializedModel = new JSONObject(this.serializer.serialize(deserializedModel));
        this.validator.validateObject(serializedModel, resourceSchemaJSONObject);
    }

    /**
     * Transforms the incoming request to the subset of typed models which the
     * handler implementor needs
     *
     * @param request The request as passed from the caller (e.g; CloudFormation)
     *            which contains additional context to inform the Wrapper itself,
     *            and is not needed by the handler implementations
     * @return A converted ResourceHandlerRequest model
     */
    protected abstract ResourceHandlerRequest<ResourceT> transform(HandlerRequest<ResourceT, CallbackT, ConfigurationT> request)
        throws IOException;

    /**
     * Handler implementation should implement this method to provide the schema for
     * validation
     *
     * @return An JSONObject of the resource schema for the provider
     */
    protected abstract JSONObject provideResourceSchemaJSONObject();

    /**
     * Handler implementation should implement this method to provide any
     * resource-level tags defined for their resource type
     *
     * @return An JSONObject of the resource schema for the provider
     */
    protected abstract Map<String, String> provideResourceDefinedTags(ResourceT resourceModel);

    /**
     * Implemented by the handler package as the key entry point.
     *
     * @param proxy Amazon webservice proxy to inject credentials correctly.
     * @param request incoming request for the call
     * @param action which action to take {@link Action#CREATE},
     *            {@link Action#DELETE}, {@link Action#READ} {@link Action#LIST} or
     *            {@link Action#UPDATE}
     * @param callbackContext the callback context to handle reentrant calls
     * @param typeConfiguration the configuration for the type set by type consumer
     * @return progress event indicating success, in progress with delay callback or
     *         failed state
     * @throws Exception propagate any unexpected errors
     */
    public abstract ProgressEvent<ResourceT, CallbackT> invokeHandler(AmazonWebServicesClientProxy proxy,
                                                                      ResourceHandlerRequest<ResourceT> request,
                                                                      Action action,
                                                                      CallbackT callbackContext,
                                                                      ConfigurationT typeConfiguration)
        throws Exception;

    /*
     * null-safe exception metrics delivery
     */
    private void publishExceptionMetric(final Action action, final Throwable ex, final HandlerErrorCode handlerErrorCode) {
        if (this.metricsPublisherProxy != null) {
            this.metricsPublisherProxy.publishExceptionMetric(Instant.now(), action, ex, handlerErrorCode);
        } else {
            // The platform logger's is the only fallback if metrics publisher proxy is not
            // initialized.
            platformLoggerProxy.log(ex.toString());
        }
    }

    /*
     * null-safe exception metrics delivery
     */
    private void publishExceptionCodeAndCountMetrics(final Action action, final HandlerErrorCode handlerErrorCode) {
        if (this.metricsPublisherProxy != null) {
            this.metricsPublisherProxy.publishExceptionByErrorCodeAndCountBulkMetrics(Instant.now(), action, handlerErrorCode);
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
        } else {
            // The platform logger's is the only fallback if metrics publisher proxy is not
            // initialized.
            platformLoggerProxy.log(message);
        }
    }

    protected abstract TypeReference<HandlerRequest<ResourceT, CallbackT, ConfigurationT>> getTypeReference();

    protected abstract TypeReference<ResourceT> getModelTypeReference();

    protected void scrubFiles() {
        try {
            FileUtils.cleanDirectory(FileUtils.getTempDirectory());
        } catch (IOException e) {
            log(e.getMessage());
            publishExceptionMetric(null, new FileScrubberException(e), HandlerErrorCode.InternalFailure);
        }
    }

    /**
     * Combines the tags supplied by the caller (e.g; CloudFormation) into a single
     * Map which represents the desired final set of tags to be applied to this
     * resource. User-defined tags
     *
     * @param request The request object contains the new set of tags to be applied
     *            at a Stack level. These will be overridden with any resource-level
     *            tags which are specified as a direct resource property.
     * @return a Map of Tag names to Tag values
     */
    @VisibleForTesting
    protected Map<String, String> getDesiredResourceTags(final HandlerRequest<ResourceT, CallbackT, ConfigurationT> request) {
        Map<String, String> desiredResourceTags = new HashMap<>();
        JSONObject object;

        if (request != null && request.getRequestData() != null) {
            replaceInMap(desiredResourceTags, request.getRequestData().getStackTags());
            replaceInMap(desiredResourceTags, provideResourceDefinedTags(request.getRequestData().getResourceProperties()));
        }

        return desiredResourceTags;
    }

    /**
     * Combines the previous tags supplied by the caller (e.g; CloudFormation) into
     * a single Map which represents the desired final set of tags that were applied
     * to this resource in the previous state.
     *
     * @param request The request object contains the new set of tags to be applied
     *            at a Stack level. These will be overridden with any resource-level
     *            tags which are specified as a direct resource property.
     * @return a Map of Tag names to Tag values
     */
    @VisibleForTesting
    protected Map<String, String> getPreviousResourceTags(final HandlerRequest<ResourceT, CallbackT, ConfigurationT> request) {
        Map<String, String> previousResourceTags = new HashMap<>();

        if (request != null && request.getRequestData() != null) {
            replaceInMap(previousResourceTags, request.getRequestData().getPreviousStackTags());
            if (request.getRequestData().getPreviousResourceProperties() != null) {
                replaceInMap(previousResourceTags,
                    provideResourceDefinedTags(request.getRequestData().getPreviousResourceProperties()));
            }
        }

        return previousResourceTags;
    }

    @VisibleForTesting
    protected String getStackId(final HandlerRequest<ResourceT, CallbackT, ConfigurationT> request) {
        if (request != null) {
            return request.getStackId();
        }

        return null;
    }

    private void replaceInMap(final Map<String, String> targetMap, final Map<String, String> sourceMap) {
        if (targetMap == null) {
            return;
        }
        if (sourceMap == null || sourceMap.isEmpty()) {
            return;
        }

        targetMap.putAll(sourceMap);
    }
}
