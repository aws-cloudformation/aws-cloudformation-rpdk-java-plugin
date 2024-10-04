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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import com.amazonaws.AmazonServiceException;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.cloudformation.encryption.KMSCipher;
import software.amazon.cloudformation.exceptions.EncryptionException;
import software.amazon.cloudformation.exceptions.ResourceNotFoundException;
import software.amazon.cloudformation.exceptions.TerminalException;
import software.amazon.cloudformation.injection.CredentialsProvider;
import software.amazon.cloudformation.loggers.CloudWatchLogPublisher;
import software.amazon.cloudformation.loggers.LogPublisher;
import software.amazon.cloudformation.metrics.MetricsPublisher;
import software.amazon.cloudformation.proxy.Credentials;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.hook.HookHandlerRequest;
import software.amazon.cloudformation.proxy.hook.HookProgressEvent;
import software.amazon.cloudformation.proxy.hook.HookStatus;
import software.amazon.cloudformation.resource.SchemaValidator;
import software.amazon.cloudformation.resource.Serializer;
import software.amazon.cloudformation.resource.Validator;

@ExtendWith(MockitoExtension.class)
public class HookWrapperTest {

    private static final String TEST_DATA_BASE_PATH = "src/test/java/software/amazon/cloudformation/data/hook/%s";

    @Mock
    private CredentialsProvider providerLoggingCredentialsProvider;

    @Mock
    private MetricsPublisher providerMetricsPublisher;

    @Mock
    private CloudWatchLogPublisher providerEventsLogger;

    @Mock
    private LogPublisher platformEventsLogger;

    @Mock
    private SchemaValidator validator;

    @Mock
    private HookHandlerRequest hookHandlerRequest;

    @Mock
    private SdkHttpClient httpClient;

    @Mock
    private KMSCipher cipher;

    private HookWrapperOverride wrapper;

    @BeforeEach
    public void initWrapper() {
        wrapper = new HookWrapperOverride(providerLoggingCredentialsProvider, platformEventsLogger, providerEventsLogger,
                                          providerMetricsPublisher, validator, httpClient, cipher);
    }

    private static InputStream loadRequestStream(final String fileName) {
        final File file = new File(String.format(TEST_DATA_BASE_PATH, fileName));

        try {
            return new FileInputStream(file);
        } catch (final FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void verifyInitialiseRuntime() {
        verify(providerLoggingCredentialsProvider).setCredentials(any(Credentials.class));
        verify(providerMetricsPublisher).refreshClient();
    }

    private void verifyHandlerResponse(final OutputStream out, final HookProgressEvent<TestContext> expected) throws IOException {
        final Serializer serializer = new Serializer();
        final HookProgressEvent<TestContext> handlerResponse = serializer.deserialize(out.toString(),
            new TypeReference<HookProgressEvent<TestContext>>() {
            });

        assertThat(handlerResponse.getClientRequestToken()).isEqualTo(expected.getClientRequestToken());
        assertThat(handlerResponse.getHookStatus()).isEqualTo(expected.getHookStatus());
        assertThat(handlerResponse.getErrorCode()).isEqualTo(expected.getErrorCode());
        assertThat(handlerResponse.getResult()).isEqualTo(expected.getResult());
        assertThat(handlerResponse.getCallbackContext()).isEqualTo(expected.getCallbackContext());
        assertThat(handlerResponse.getCallbackDelaySeconds()).isEqualTo(expected.getCallbackDelaySeconds());
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request.json,CREATE_PRE_PROVISION", "preUpdate.request.json,UPDATE_PRE_PROVISION",
        "preDelete.request.json,DELETE_PRE_PROVISION" })
    public void invokeHandler_nullResponse_returnsFailure(final String requestDataPath, final String invocationPointString)
        throws IOException {
        final HookInvocationPoint invocationPoint = HookInvocationPoint.valueOf(invocationPointString);

        // a null response is a terminal fault
        wrapper.setInvokeHandlerResponse(null);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));
        wrapper.setTransformResponse(hookHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // validation failure metric should be published for final error handling
            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(HookInvocationPoint.class),
                any(TerminalException.class), any(HandlerErrorCode.class));

            // all metrics should be published even on terminal failure
            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(invocationPoint));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(invocationPoint), anyLong());

            verify(providerEventsLogger).refreshClient();
            verify(providerEventsLogger, times(2)).publishLogEvent(any());
            verifyNoMoreInteractions(providerEventsLogger);

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").errorCode(HandlerErrorCode.InternalFailure)
                    .hookStatus(HookStatus.FAILED).message("Handler failed to provide a response.").build());

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.getRequest()).isEqualTo(hookHandlerRequest);
            assertThat(wrapper.invocationPoint).isEqualTo(invocationPoint);
            assertThat(wrapper.callbackContext).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request-without-logging-credentials.json,CREATE_PRE_PROVISION" })
    public void invokeHandler_without_customerLoggingCredentials(final String requestDataPath, final String invocationPointString)
        throws IOException {
        final HookInvocationPoint invocationPoint = HookInvocationPoint.valueOf(invocationPointString);

        // a null response is a terminal fault
        wrapper.setInvokeHandlerResponse(null);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));
        wrapper.setTransformResponse(hookHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and provider dependencies not setup as no
            // credentials provided
            verify(providerLoggingCredentialsProvider, times(0)).setCredentials(any(Credentials.class));
            verify(providerMetricsPublisher, times(0)).refreshClient();

            // validation failure metric should not be published since provider metric
            // publisher is not setup
            verify(providerMetricsPublisher, times(0)).publishExceptionMetric(any(Instant.class), any(HookInvocationPoint.class),
                any(TerminalException.class), any(HandlerErrorCode.class));

            // no metrics should be published since provider metric publisher is not setup
            verify(providerMetricsPublisher, times(0)).publishInvocationMetric(any(Instant.class), eq(invocationPoint));
            verify(providerMetricsPublisher, times(0)).publishDurationMetric(any(Instant.class), eq(invocationPoint), anyLong());

            verifyNoMoreInteractions(providerEventsLogger);

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").errorCode(HandlerErrorCode.InternalFailure)
                    .hookStatus(HookStatus.FAILED).message("Handler failed to provide a response.").build());
        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request.json,CREATE_PRE_PROVISION", "preUpdate.request.json,UPDATE_PRE_PROVISION",
        "preDelete.request.json,DELETE_PRE_PROVISION" })
    public void invokeHandler_handlerFailed_returnsFailure(final String requestDataPath, final String invocationPointString)
        throws IOException {
        final HookInvocationPoint invocationPoint = HookInvocationPoint.valueOf(invocationPointString);

        // explicit fault response is treated as an unsuccessful synchronous completion
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.FAILED).errorCode(HandlerErrorCode.InternalFailure).message("Custom Fault").build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(hookHandlerRequest);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // verify output response
            verifyHandlerResponse(out, HookProgressEvent.<TestContext>builder().clientRequestToken("123456")
                .errorCode(HandlerErrorCode.InternalFailure).hookStatus(HookStatus.FAILED).message("Custom Fault").build());

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.getRequest()).isEqualTo(hookHandlerRequest);
            assertThat(wrapper.invocationPoint).isEqualTo(invocationPoint);
            assertThat(wrapper.callbackContext).isNull();
        }
    }

    @Test
    public void invokeHandler_withNullInput() throws IOException {
        // explicit fault response is treated as an unsuccessful synchronous completion
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.FAILED).errorCode(HandlerErrorCode.InternalFailure).message("Custom Fault").build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(hookHandlerRequest);

        try (final InputStream in = null; final OutputStream out = new ByteArrayOutputStream()) {
            wrapper.processRequest(in, out);

            verifyNoMoreInteractions(platformEventsLogger, providerMetricsPublisher, providerEventsLogger);
        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request.json,CREATE_PRE_PROVISION", "preUpdate.request.json,UPDATE_PRE_PROVISION",
        "preDelete.request.json,DELETE_PRE_PROVISION" })
    public void invokeHandler_CompleteSynchronously_returnsSuccess(final String requestDataPath,
                                                                   final String invocationPointString)
        throws IOException {
        final HookInvocationPoint invocationPoint = HookInvocationPoint.valueOf(invocationPointString);

        // if the handler responds Complete, this is treated as a successful synchronous
        // completion
        final ProgressEvent<TestModel,
            TestContext> pe = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.SUCCESS).build();
        wrapper.setInvokeHandlerResponse(pe);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        wrapper.setTransformResponse(hookHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").hookStatus(HookStatus.SUCCESS).build());

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.getRequest()).isEqualTo(hookHandlerRequest);
            assertThat(wrapper.invocationPoint).isEqualTo(invocationPoint);
            assertThat(wrapper.callbackContext).isNull();
        }
    }

    @Test
    public void invokeHandler_DependenciesInitialised_CompleteSynchronously_returnsSuccess() throws IOException {
        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        // if the handler responds Complete, this is treated as a successful synchronous
        // completion
        final ProgressEvent<TestModel,
            TestContext> pe = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.SUCCESS).build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(hookHandlerRequest);

        // use a request context in our payload to bypass certain callbacks
        try (final InputStream in = loadRequestStream("preCreate.with-request-context.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // simply ensure all dependencies were setup correctly - behaviour is tested
            // through mocks
            assertThat(wrapper.serializer).isNotNull();
            assertThat(wrapper.loggerProxy).isNotNull();
            assertThat(wrapper.platformLoggerProxy).isNotNull();
            assertThat(wrapper.metricsPublisherProxy).isNotNull();
            assertThat(wrapper.providerCredentialsProvider).isNotNull();
            assertThat(wrapper.providerCloudWatchProvider).isNotNull();
            assertThat(wrapper.cloudWatchLogsProvider).isNotNull();
            assertThat(wrapper.validator).isNotNull();
        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request.json,CREATE_PRE_PROVISION", "preUpdate.request.json,UPDATE_PRE_PROVISION",
        "preDelete.request.json,DELETE_PRE_PROVISION" })
    public void invokeHandler_InProgress_returnsInProgress(final String requestDataPath, final String invocationPointString)
        throws IOException {
        final HookInvocationPoint invocationPoint = HookInvocationPoint.valueOf(invocationPointString);

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent<TestModel,
            TestContext> pe = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.IN_PROGRESS).build();
        wrapper.setInvokeHandlerResponse(pe);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));
        wrapper.setTransformResponse(hookHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(invocationPoint));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(invocationPoint), anyLong());

            // verify exception and error code count published
            verify(providerMetricsPublisher, times(1)).publishExceptionByErrorCodeAndCountBulkMetrics(any(Instant.class),
                any(HookInvocationPoint.class), isNull());

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").hookStatus(HookStatus.IN_PROGRESS).build());

            // validation failure metric should not be published
            verifyNoMoreInteractions(providerMetricsPublisher);

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.getRequest()).isEqualTo(hookHandlerRequest);
            assertThat(wrapper.invocationPoint).isEqualTo(invocationPoint);
            assertThat(wrapper.callbackContext).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.with-request-context.request.json,CREATE_PRE_PROVISION",
        "preUpdate.with-request-context.request.json,UPDATE_PRE_PROVISION",
        "preDelete.with-request-context.request.json,DELETE_PRE_PROVISION" })
    public void reInvokeHandler_InProgress_returnsInProgress(final String requestDataPath, final String invocationPointString)
        throws IOException {
        final HookInvocationPoint invocationPoint = HookInvocationPoint.valueOf(invocationPointString);

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used, and any
        // interval >= 1 minute is scheduled
        // against CloudWatch. Shorter intervals are able to run locally within same
        // function context if runtime permits
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.IN_PROGRESS).callbackDelaySeconds(60).build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(hookHandlerRequest);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(invocationPoint));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(invocationPoint), anyLong());

            // verify exception and error code count published
            verify(providerMetricsPublisher, times(1)).publishExceptionByErrorCodeAndCountBulkMetrics(any(Instant.class),
                any(HookInvocationPoint.class), isNull());

            // validation failure metric should not be published
            verifyNoMoreInteractions(providerMetricsPublisher);

            // verify output response
            verifyHandlerResponse(out, HookProgressEvent.<TestContext>builder().clientRequestToken("123456")
                .hookStatus(HookStatus.IN_PROGRESS).callbackDelaySeconds(60).build());
        }
    }

    @Test
    public void invokeHandler_withoutCallerCredentials_passesNoAWSProxy() throws IOException {
        final TestModel model = new TestModel();
        model.setProperty1("abc");
        model.setProperty2(123);
        wrapper.setTransformResponse(hookHandlerRequest);

        // respond with immediate success to avoid callback invocation
        final ProgressEvent<TestModel,
            TestContext> pe = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.SUCCESS).build();
        wrapper.setInvokeHandlerResponse(pe);

        // without platform credentials the handler is unable to do
        // basic SDK initialization and any such request should fail fast
        try (final InputStream in = loadRequestStream("preCreate.request-without-caller-credentials.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").hookStatus(HookStatus.SUCCESS).build());

            // proxy should be null by virtue of having not had callerCredentials passed in
            assertThat(wrapper.awsClientProxy).isNull();
        }
    }

    @Test
    public void invokeHandler_withDefaultInjection_returnsSuccess() throws IOException {
        final TestModel model = new TestModel();
        model.setProperty1("abc");
        model.setProperty2(123);
        wrapper.setTransformResponse(hookHandlerRequest);

        // respond with immediate success to avoid callback invocation
        final ProgressEvent<TestModel,
            TestContext> pe = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.SUCCESS).build();
        wrapper.setInvokeHandlerResponse(pe);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        // without platform credentials the handler is unable to do
        // basic SDK initialization and any such request should fail fast
        try (final InputStream in = loadRequestStream("preCreate.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").hookStatus(HookStatus.SUCCESS).build());

            // proxy uses caller credentials and will be injected
            assertThat(wrapper.awsClientProxy).isNotNull();
        }
    }

    @Test
    public void invokeHandler_withDefaultInjection_returnsInProgress() throws IOException {
        final TestModel model = new TestModel();
        model.setProperty1("abc");
        model.setProperty2(123);
        wrapper.setTransformResponse(hookHandlerRequest);

        // respond with immediate success to avoid callback invocation
        final ProgressEvent<TestModel,
            TestContext> pe = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.IN_PROGRESS).build();
        wrapper.setInvokeHandlerResponse(pe);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        // without platform credentials the handler is unable to do
        // basic SDK initialization and any such request should fail fast
        try (final InputStream in = loadRequestStream("preCreate.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            wrapper.processRequest(in, out);

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").hookStatus(HookStatus.IN_PROGRESS).build());
        }
    }

    @Test
    public void invokeHandler_platformCredentialsRefreshedOnEveryInvoke() throws IOException {
        try (InputStream in = loadRequestStream("preCreate.request.json"); OutputStream out = new ByteArrayOutputStream()) {
            wrapper.processRequest(in, out);
        }

        final Credentials expected = new Credentials("32IEHAHFIAG538KYASAI", "0O2hop/5vllVHjbA8u52hK8rLcroZpnL5NPGOi66",
                                                     "gqe6eIsFPHOlfhc3RKl5s5Y6Dy9PYvN1CEYsswz5TQUsE8WfHD6LPK549euXm4Vn4INBY9nMJ1cJe2mxTYFdhWHSnkOQv2SHemal");
        // invoke the same wrapper instance again to ensure client is refreshed
        try (InputStream in = loadRequestStream("preCreate.request.with-new-credentials.json");
            OutputStream out = new ByteArrayOutputStream()) {
            wrapper.processRequest(in, out);
        }

        final Credentials expectedNew = new Credentials("GT530IJDHALYZQSZZ8XG", "UeJEwC/dqcYEn2viFd5TjKjR5TaMOfdeHrlLXxQL",
                                                        "469gs8raWJCaZcItXhGJ7dt3urI13fOTcde6ibhuHJz6r6bRRCWvLYGvCsqrN8WUClYL9lxZHymrWXvZ9xN0GoI2LFdcAAinZk5t");
    }

    @Test
    public void invokeHandler_throwsAmazonServiceException_returnsServiceException() throws IOException {
        // exceptions are caught consistently by LambdaWrapper
        wrapper.setInvokeHandlerException(new AmazonServiceException("some error"));

        wrapper.setTransformResponse(hookHandlerRequest);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        try (final InputStream in = loadRequestStream("preCreate.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION), anyLong());

            // failure metric should be published
            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(HookInvocationPoint.class),
                any(AmazonServiceException.class), any(HandlerErrorCode.class));

            // verify output response
            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456")
                    .errorCode(HandlerErrorCode.GeneralServiceException).hookStatus(HookStatus.FAILED)
                    .message("some error (Service: null, Status Code: 0, Request ID: null, Extended Request ID: null)").build());
        }
    }

    @Test
    public void invokeHandler_throwsSDK2ServiceException_returnsServiceException() throws IOException {
        // exceptions are caught consistently by LambdaWrapper
        wrapper.setInvokeHandlerException(
            AwsServiceException.builder().awsErrorDetails(AwsErrorDetails.builder().errorMessage("some error").build()).build());

        wrapper.setTransformResponse(hookHandlerRequest);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        try (final InputStream in = loadRequestStream("preCreate.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION), anyLong());

            // failure metric should be published
            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(HookInvocationPoint.class),
                any(AwsServiceException.class), any(HandlerErrorCode.class));

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456")
                    .errorCode(HandlerErrorCode.GeneralServiceException).hookStatus(HookStatus.FAILED)
                    .message("some error (Service: null, Status Code: 0, Request ID: null, Extended Request ID: null)").build());
        }
    }

    @Test
    public void invokeHandler_throwsThrottlingException_returnsCFNThrottlingException() throws IOException {
        AmazonServiceException exception = new AmazonServiceException("Rate Exceed ...");
        exception.setErrorCode("Throttling");
        wrapper.setInvokeHandlerException(exception);

        wrapper.setTransformResponse(hookHandlerRequest);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        try (final InputStream in = loadRequestStream("preCreate.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION), anyLong());

            // failure metric should be published
            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(HookInvocationPoint.class),
                any(AmazonServiceException.class), any(HandlerErrorCode.class));

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").errorCode(HandlerErrorCode.Throttling)
                    .hookStatus(HookStatus.FAILED)
                    .message("some error (Service: null, Status Code: 0, Request ID: null, Extended Request ID: null)").build());
        }
    }

    @Test
    public void invokeHandler_throwsServiceInternalError_returnsCFNServiceInternalErrorException() throws IOException {
        AmazonServiceException exception = new AmazonServiceException("Amazon Service Error");
        exception.setStatusCode(HttpStatusCode.INTERNAL_SERVER_ERROR);
        wrapper.setInvokeHandlerException(exception);

        wrapper.setTransformResponse(hookHandlerRequest);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        try (final InputStream in = loadRequestStream("preCreate.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION), anyLong());

            // failure metric should be published
            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(HookInvocationPoint.class),
                any(AmazonServiceException.class), any(HandlerErrorCode.class));

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456")
                    .errorCode(HandlerErrorCode.ServiceInternalError).hookStatus(HookStatus.FAILED)
                    .message("some error (Service: null, Status Code: 0, Request ID: null, Extended Request ID: null)").build());
        }
    }

    @Test
    public void invokeHandler_throwsInvalidRequestError_returnsCFNInvalidRequestException() throws IOException {
        AmazonServiceException exception = new AmazonServiceException("Amazon Service Error");
        exception.setStatusCode(HttpStatusCode.BAD_REQUEST);
        wrapper.setInvokeHandlerException(exception);

        wrapper.setTransformResponse(hookHandlerRequest);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        try (final InputStream in = loadRequestStream("preCreate.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION), anyLong());

            // failure metric should be published
            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(HookInvocationPoint.class),
                any(AmazonServiceException.class), any(HandlerErrorCode.class));

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").errorCode(HandlerErrorCode.InvalidRequest)
                    .hookStatus(HookStatus.FAILED)
                    .message("some error (Service: null, Status Code: 0, Request ID: null, Extended Request ID: null)").build());
        }
    }

    @Test
    public void invokeHandler_throwsHookNotFoundException_returnsNotFound() throws IOException {
        // exceptions are caught consistently by LambdaWrapper
        wrapper.setInvokeHandlerException(new ResourceNotFoundException("AWS::Test::TestModel", "id-1234"));

        wrapper.setTransformResponse(hookHandlerRequest);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        try (final InputStream in = loadRequestStream("preCreate.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION), anyLong());

            // failure metric should be published
            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(HookInvocationPoint.class),
                any(ResourceNotFoundException.class), any(HandlerErrorCode.class));

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").errorCode(HandlerErrorCode.NotFound)
                    .hookStatus(HookStatus.FAILED)
                    .message("Resource of type 'AWS::Test::TestModel' with identifier 'id-1234' was not found.").build());
        }
    }

    @Test
    public void invokeHandler_throwsEncryptionException_returnsAccessDenied() throws IOException {
        wrapper.setTransformResponse(hookHandlerRequest);

        lenient().when(cipher.decryptCredentials(any())).thenThrow(new EncryptionException("Failed to decrypt credentials."));

        try (final InputStream in = loadRequestStream("preCreate.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // all metrics should be published, once for a single invocation
            verify(providerMetricsPublisher, times(0)).publishInvocationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION));
            verify(providerMetricsPublisher, times(0)).publishDurationMetric(any(Instant.class),
                eq(HookInvocationPoint.CREATE_PRE_PROVISION), anyLong());

            // failure metric should be published
            verify(providerMetricsPublisher, times(0)).publishExceptionMetric(any(Instant.class), any(HookInvocationPoint.class),
                any(ResourceNotFoundException.class), any(HandlerErrorCode.class));

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").errorCode(HandlerErrorCode.AccessDenied)
                    .hookStatus(HookStatus.FAILED).message("Failed to decrypt credentials.").build());
        }
    }

    @Test
    public void invokeHandler_metricPublisherThrowable_returnsFailureResponse() throws IOException {
        // simulate runtime Errors in the metrics publisher (such as dependency
        // resolution conflicts)
        doThrow(new Error("not an Exception")).when(providerMetricsPublisher).publishExceptionMetric(any(),
            any(HookInvocationPoint.class), any(), any());

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        try (final InputStream in = loadRequestStream("preCreate.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            try {
                wrapper.processRequest(in, out);
            } catch (final Error e) {
                // ignore so we can perform verifications
            }

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // verify exception and error code count published
            verify(providerMetricsPublisher, times(1)).publishExceptionByErrorCodeAndCountBulkMetrics(any(Instant.class),
                any(HookInvocationPoint.class), eq(HandlerErrorCode.InternalFailure));

            // verify output response
            verifyHandlerResponse(out, HookProgressEvent.<TestContext>builder().clientRequestToken("123456")
                .errorCode(HandlerErrorCode.InternalFailure).hookStatus(HookStatus.FAILED).message("not an Exception").build());
        }
    }

    @Test
    public void invokeHandler_withInvalidPayload_returnsFailureResponse() throws IOException {
        try (final InputStream in = new ByteArrayInputStream(new byte[0]); final OutputStream out = new ByteArrayOutputStream()) {
            try {
                wrapper.processRequest(in, out);
            } catch (final Error e) {
                // ignore so we can perform verifications
            }

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().errorCode(HandlerErrorCode.InternalFailure).hookStatus(HookStatus.FAILED)
                    .message("A JSONObject text must begin with '{' at 0 [character 1 line 1]").build());
        }
    }

    @Test
    public void invokeHandler_withNullInputStream_returnsFailureResponse() throws IOException {
        try (final OutputStream out = new ByteArrayOutputStream()) {
            try {
                wrapper.processRequest(null, out);
            } catch (final Error e) {
                // ignore so we can perform verifications
            }

            // verify output response
            verifyHandlerResponse(out, HookProgressEvent.<TestContext>builder().errorCode(HandlerErrorCode.InternalFailure)
                .hookStatus(HookStatus.FAILED).message("No request object received").build());
        }
    }

    @Test
    public void invokeHandler_withEmptyPayload_returnsFailure() throws IOException {
        try (final InputStream in = loadRequestStream("empty.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            try {
                wrapper.processRequest(in, out);
            } catch (final Error e) {
                // ignore so we can perform verifications
            }

            // verify output response
            verifyHandlerResponse(out, HookProgressEvent.<TestContext>builder().errorCode(HandlerErrorCode.InternalFailure)
                .hookStatus(HookStatus.FAILED).message("Invalid request object received. Target Model can not be null.").build());
        }
    }

    @Test
    public void invokeHandler_without_targetModel_returnsFailure() throws IOException {
        try (final InputStream in = loadRequestStream("preCreate.request-without-target-model.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            try {
                wrapper.processRequest(in, out);
            } catch (final Error e) {
                // ignore so we can perform verifications
            }

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").hookStatus(HookStatus.FAILED)
                    .errorCode(HandlerErrorCode.InternalFailure).hookStatus(HookStatus.FAILED)
                    .message("Invalid request object received. Target Model can not be null.").build());
        }
    }

    @Test
    public void invokeHandler_with_invalid_actionInvocationPoint_returnsFailure() throws IOException {
        try (final InputStream in = loadRequestStream("preCreate.request-with-invalid-actionInvocationPoint.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            try {
                wrapper.processRequest(in, out);
            } catch (final Error e) {
                // ignore so we can perform verifications
            }

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().hookStatus(HookStatus.FAILED).errorCode(HandlerErrorCode.InternalFailure)
                    .hookStatus(HookStatus.FAILED).message("Invalid hook invocation request object received").build());
        }
    }

    @Test
    public void stringifiedPayload_validation_successful() throws IOException {
        // this test ensures that validation on the payload is performed
        // against the serialized
        // model rather than the raw payload. This allows the handlers to accept
        // incoming payloads that
        // may have quoted values where the JSON Serialization is still able to
        // construct a valid POJO
        SchemaValidator validator = new Validator();
        final HookWrapperOverride wrapper = new HookWrapperOverride(providerLoggingCredentialsProvider, platformEventsLogger,
                                                                    providerEventsLogger, providerMetricsPublisher, validator,
                                                                    httpClient, cipher);

        // explicit fault response is treated as an unsuccessful synchronous completion
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.SUCCESS).message("Handler was invoked").build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(hookHandlerRequest);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        try (final InputStream in = loadRequestStream("preCreate.request-with-stringified.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            wrapper.processRequest(in, out);

            // verify output response
            verifyHandlerResponse(out, HookProgressEvent.<TestContext>builder().clientRequestToken("123456")
                .message("Handler was invoked").hookStatus(HookStatus.SUCCESS).build());

        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request-without-encryption-key-arn.json,CREATE_PRE_PROVISION" })
    public void invokeHandler_without_encryptionKeyARN(final String requestDataPath, final String invocationPointString)
        throws IOException {
        final HookInvocationPoint invocationPoint = HookInvocationPoint.valueOf(invocationPointString);
        final HookWrapperOverride wrapper = new HookWrapperOverride(providerLoggingCredentialsProvider, platformEventsLogger,
                                                                    providerEventsLogger, providerMetricsPublisher, validator,
                                                                    httpClient, null);

        // a null response is a terminal fault
        wrapper.setInvokeHandlerResponse(null);

        wrapper.setTransformResponse(hookHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and provider dependencies not setup since
            // no credentials' decryption key provided
            verify(providerLoggingCredentialsProvider, times(0)).setCredentials(any(Credentials.class));

            // validation failure metric should not be published since provider metric
            // publisher is not setup
            verify(providerMetricsPublisher, times(0)).publishExceptionMetric(any(Instant.class), any(HookInvocationPoint.class),
                any(TerminalException.class), any(HandlerErrorCode.class));

            // no metrics should be published since provider metric publisher is not setup
            verify(providerMetricsPublisher, times(0)).publishInvocationMetric(any(Instant.class), eq(invocationPoint));
            verify(providerMetricsPublisher, times(0)).publishDurationMetric(any(Instant.class), eq(invocationPoint), anyLong());

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").errorCode(HandlerErrorCode.InternalFailure)
                    .hookStatus(HookStatus.FAILED).message("Handler failed to provide a response.").build());
        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request-without-encryption-key-role.json,CREATE_PRE_PROVISION" })
    public void invokeHandler_without_encryptionKeyRole(final String requestDataPath, final String invocationPointString)
        throws IOException {
        final HookInvocationPoint invocationPoint = HookInvocationPoint.valueOf(invocationPointString);
        final HookWrapperOverride wrapper = new HookWrapperOverride(providerLoggingCredentialsProvider, platformEventsLogger,
                                                                    providerEventsLogger, providerMetricsPublisher, validator,
                                                                    httpClient, null);

        // a null response is a terminal fault
        wrapper.setInvokeHandlerResponse(null);

        wrapper.setTransformResponse(hookHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            // verify initialiseRuntime was called and provider dependencies not setup since
            // no credentials' decryption key role provided
            verify(providerLoggingCredentialsProvider, times(0)).setCredentials(any(Credentials.class));

            // validation failure metric should not be published since provider metric
            // publisher is not setup
            verify(providerMetricsPublisher, times(0)).publishExceptionMetric(any(Instant.class), any(HookInvocationPoint.class),
                any(TerminalException.class), any(HandlerErrorCode.class));

            // no metrics should be published since provider metric publisher is not setup
            verify(providerMetricsPublisher, times(0)).publishInvocationMetric(any(Instant.class), eq(invocationPoint));
            verify(providerMetricsPublisher, times(0)).publishDurationMetric(any(Instant.class), eq(invocationPoint), anyLong());

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").errorCode(HandlerErrorCode.InternalFailure)
                    .hookStatus(HookStatus.FAILED).message("Handler failed to provide a response.").build());
        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request-with-unencrypted-credentials.json,CREATE_PRE_PROVISION" })
    public void invokeHandler_with_unencrypted_credentials(final String requestDataPath, final String invocationPointString)
        throws IOException {
        final HookInvocationPoint invocationPoint = HookInvocationPoint.valueOf(invocationPointString);

        wrapper = new HookWrapperOverride(providerLoggingCredentialsProvider, platformEventsLogger, providerEventsLogger,
                                          providerMetricsPublisher, validator, httpClient, null);

        // if the handler responds Complete, this is treated as a successful synchronous
        // completion
        final ProgressEvent<TestModel,
            TestContext> pe = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.SUCCESS).build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(hookHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {

            wrapper.processRequest(in, out);

            verify(cipher, times(0)).decryptCredentials(anyString());
            verify(providerLoggingCredentialsProvider)
                .setCredentials(eq((new Credentials("providerAccessKeyId", "providerSecretAccessKey", "providerSessionToken"))));
            verify(providerMetricsPublisher).refreshClient();

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").hookStatus(HookStatus.SUCCESS).build());

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.getRequest()).isEqualTo(hookHandlerRequest);
            assertThat(wrapper.invocationPoint).isEqualTo(invocationPoint);
            assertThat(wrapper.callbackContext).isNull();
        }
    }
}
