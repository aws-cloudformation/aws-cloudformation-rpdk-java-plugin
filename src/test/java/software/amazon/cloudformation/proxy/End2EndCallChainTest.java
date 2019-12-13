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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.joda.time.Instant;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.stubbing.Answer;

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.cloudwatchevents.CloudWatchEventsClient;
import software.amazon.awssdk.services.cloudwatchevents.model.PutRuleRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.PutRuleResponse;
import software.amazon.awssdk.services.cloudwatchevents.model.PutTargetsRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.PutTargetsResponse;
import software.amazon.cloudformation.Action;
import software.amazon.cloudformation.Response;
import software.amazon.cloudformation.injection.CloudWatchEventsProvider;
import software.amazon.cloudformation.injection.CredentialsProvider;
import software.amazon.cloudformation.loggers.CloudWatchLogPublisher;
import software.amazon.cloudformation.loggers.LogPublisher;
import software.amazon.cloudformation.metrics.MetricsPublisher;
import software.amazon.cloudformation.proxy.handler.Model;
import software.amazon.cloudformation.proxy.handler.ServiceHandlerWrapper;
import software.amazon.cloudformation.proxy.service.AccessDenied;
import software.amazon.cloudformation.proxy.service.CreateRequest;
import software.amazon.cloudformation.proxy.service.CreateResponse;
import software.amazon.cloudformation.proxy.service.DescribeRequest;
import software.amazon.cloudformation.proxy.service.DescribeResponse;
import software.amazon.cloudformation.proxy.service.ExistsException;
import software.amazon.cloudformation.proxy.service.NotFoundException;
import software.amazon.cloudformation.proxy.service.ServiceClient;
import software.amazon.cloudformation.proxy.service.ThrottleException;
import software.amazon.cloudformation.resource.Serializer;
import software.amazon.cloudformation.resource.Validator;
import software.amazon.cloudformation.scheduler.CloudWatchScheduler;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class End2EndCallChainTest {

    static final AwsServiceException.Builder builder = mock(AwsServiceException.Builder.class);

    //
    // The same that is asserted inside the ServiceClient
    //
    private final AwsSessionCredentials MockCreds = AwsSessionCredentials.create("accessKeyId", "secretKey", "token");
    private final Credentials credentials = new Credentials(MockCreds.accessKeyId(), MockCreds.secretAccessKey(),
                                                            MockCreds.sessionToken());
    @SuppressWarnings("unchecked")
    private final CallbackAdapter<Model> adapter = mock(CallbackAdapter.class);

    @Test
    public void happyCase() {
        final AmazonWebServicesClientProxy proxy = new AmazonWebServicesClientProxy(mock(LoggerProxy.class), credentials,
                                                                                    () -> Duration.ofMinutes(10).getSeconds());

        final Model model = Model.builder().repoName("repo").build();
        StdCallbackContext context = new StdCallbackContext();
        final ServiceClient serviceClient = mock(ServiceClient.class);
        when(serviceClient.createRepository(any(CreateRequest.class)))
            .thenReturn(new CreateResponse.Builder().repoName(model.getRepoName()).build());

        ProxyClient<ServiceClient> client = proxy.newProxy(() -> serviceClient);

        ProgressEvent<Model,
            StdCallbackContext> event = proxy.initiate("client:createRepository", client, model, context)
                .request((m) -> new CreateRequest.Builder().repoName(m.getRepoName()).build())
                .call((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::createRepository))
                .done(r -> ProgressEvent.success(model, context));

        assertThat(event.getStatus()).isEqualTo(OperationStatus.SUCCESS);

        // replay, should get the same result.
        event = proxy.initiate("client:createRepository", client, model, context)
            .request((m) -> new CreateRequest.Builder().repoName(m.getRepoName()).build())
            .call((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::createRepository))
            .done(r -> ProgressEvent.success(model, context));
        //
        // Verify that we only got called. During replay we should have been skipped.
        //
        verify(serviceClient).createRepository(any(CreateRequest.class));

        assertThat(event.getStatus()).isEqualTo(OperationStatus.SUCCESS);

        // Now a separate request
        final SdkHttpResponse sdkHttpResponse = mock(SdkHttpResponse.class);
        when(sdkHttpResponse.statusCode()).thenReturn(500);

        //
        // Now reset expectation to fail with already exists
        //
        final ExistsException exists = new ExistsException(mock(AwsServiceException.Builder.class)) {
            private static final long serialVersionUID = 1L;

            @Override
            public AwsErrorDetails awsErrorDetails() {
                return AwsErrorDetails.builder().errorCode("AlreadyExists").errorMessage("Repo already exists")
                    .sdkHttpResponse(sdkHttpResponse).build();
            }
        };
        when(serviceClient.createRepository(any(CreateRequest.class))).thenThrow(exists);
        StdCallbackContext newContext = new StdCallbackContext();
        event = proxy.initiate("client:createRepository", client, model, newContext)
            .request((m) -> new CreateRequest.Builder().repoName(m.getRepoName()).build())
            .call((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::createRepository))
            .done(r -> ProgressEvent.success(model, context));

        assertThat(event.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(event.getMessage()).contains("AlreadyExists");
    }

    private HandlerRequest<Model, StdCallbackContext> prepareRequest(Model model) throws Exception {
        HandlerRequest<Model, StdCallbackContext> request = new HandlerRequest<>();
        request.setAction(Action.CREATE);
        request.setAwsAccountId("1234567891234");
        request.setBearerToken("dwezxdfgfgh");
        request.setNextToken(null);
        request.setRegion("us-east-2");
        request.setResourceType("AWS::Code::Repository");
        request.setStackId(UUID.randomUUID().toString());
        request.setResponseEndpoint("https://cloudformation.amazonaws.com");
        RequestData<Model> data = new RequestData<>();
        data.setResourceProperties(model);
        data.setPlatformCredentials(credentials);
        data.setCallerCredentials(credentials);
        request.setRequestData(data);
        return request;
    }

    private HandlerRequest<Model, StdCallbackContext> prepareRequest() throws Exception {
        return prepareRequest(Model.builder().repoName("repository").build());
    }

    private InputStream prepareStream(Serializer serializer, HandlerRequest<Model, StdCallbackContext> request) throws Exception {

        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);

        String value = serializer.serialize(request);
        writer.write(value);
        writer.flush();
        writer.close();

        return new ByteArrayInputStream(out.toByteArray());
    }

    private CredentialsProvider prepareMockProvider() {
        return new CredentialsProvider() {
            @Override
            public AwsSessionCredentials get() {
                return MockCreds;
            }

            @Override
            public void setCredentials(Credentials credentials) {

            }
        };
    }

    @Order(5)
    @Test
    public void notFound() throws Exception {
        final HandlerRequest<Model, StdCallbackContext> request = prepareRequest(Model.builder().repoName("repository").build());
        request.setAction(Action.READ);
        final Model model = request.getRequestData().getResourceProperties();
        final Serializer serializer = new Serializer();
        final InputStream stream = prepareStream(serializer, request);
        final ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        final LoggerProxy loggerProxy = mock(LoggerProxy.class);
        final CredentialsProvider platformCredentialsProvider = prepareMockProvider();
        final CredentialsProvider providerLoggingCredentialsProvider = prepareMockProvider();
        final Context cxt = mock(Context.class);
        // bail out immediately
        when(cxt.getRemainingTimeInMillis()).thenReturn(50);
        when(cxt.getLogger()).thenReturn(mock(LambdaLogger.class));

        final ServiceClient client = mock(ServiceClient.class);
        final SdkHttpResponse sdkHttpResponse = mock(SdkHttpResponse.class);
        when(sdkHttpResponse.statusCode()).thenReturn(404);
        final DescribeRequest describeRequest = new DescribeRequest.Builder().repoName(model.getRepoName()).overrideConfiguration(
            AwsRequestOverrideConfiguration.builder().credentialsProvider(StaticCredentialsProvider.create(MockCreds)).build())
            .build();
        final NotFoundException notFound = new NotFoundException(mock(AwsServiceException.Builder.class)) {
            private static final long serialVersionUID = 1L;

            @Override
            public AwsErrorDetails awsErrorDetails() {
                return AwsErrorDetails.builder().errorCode("NotFound").errorMessage("Repo not existing")
                    .sdkHttpResponse(sdkHttpResponse).build();
            }
        };
        when(client.describeRepository(eq(describeRequest))).thenThrow(notFound);

        final SdkHttpClient httpClient = mock(SdkHttpClient.class);
        final ServiceHandlerWrapper wrapper = new ServiceHandlerWrapper(adapter, platformCredentialsProvider,
                                                                        providerLoggingCredentialsProvider,
                                                                        mock(CloudWatchLogPublisher.class),
                                                                        mock(LogPublisher.class), mock(MetricsPublisher.class),
                                                                        mock(MetricsPublisher.class),
                                                                        new CloudWatchScheduler(new CloudWatchEventsProvider(platformCredentialsProvider,
                                                                                                                             httpClient) {
                                                                            @Override
                                                                            public CloudWatchEventsClient get() {
                                                                                return mock(CloudWatchEventsClient.class);
                                                                            }
                                                                        }, loggerProxy, serializer), new Validator(), serializer,
                                                                        client, httpClient);

        wrapper.handleRequest(stream, output, cxt);
        verify(client).describeRepository(eq(describeRequest));

        Response<Model> response = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
            new TypeReference<Response<Model>>() {
            });
        assertThat(response).isNotNull();
        assertThat(response.getOperationStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(response.getBearerToken()).isEqualTo("dwezxdfgfgh");
        assertThat(response.getMessage()).contains("NotFound");
    }

    @SuppressWarnings("unchecked")
    @Order(10)
    @Test
    public void createHandler() throws Exception {
        final HandlerRequest<Model, StdCallbackContext> request = prepareRequest();
        final Serializer serializer = new Serializer();
        final InputStream stream = prepareStream(serializer, request);
        final ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        final LoggerProxy loggerProxy = mock(LoggerProxy.class);
        final CredentialsProvider platformCredentialsProvider = prepareMockProvider();
        final CredentialsProvider providerLoggingCredentialsProvider = prepareMockProvider();
        final Context cxt = mock(Context.class);
        // bail out immediately
        when(cxt.getRemainingTimeInMillis()).thenReturn(50);
        when(cxt.getLogger()).thenReturn(mock(LambdaLogger.class));

        final Model model = request.getRequestData().getResourceProperties();

        final ServiceClient client = mock(ServiceClient.class);
        final CreateRequest createRequest = new CreateRequest.Builder().repoName(model.getRepoName()).overrideConfiguration(
            AwsRequestOverrideConfiguration.builder().credentialsProvider(StaticCredentialsProvider.create(MockCreds)).build())
            .build();
        when(client.createRepository(eq(createRequest)))
            .thenReturn(new CreateResponse.Builder().repoName(model.getRepoName()).build());

        final DescribeRequest describeRequest = new DescribeRequest.Builder().repoName(model.getRepoName()).overrideConfiguration(
            AwsRequestOverrideConfiguration.builder().credentialsProvider(StaticCredentialsProvider.create(MockCreds)).build())
            .build();
        final DescribeResponse describeResponse = new DescribeResponse.Builder().createdWhen(Instant.now().toDate())
            .repoName(model.getRepoName()).repoArn("some-arn").build();
        when(client.describeRepository(eq(describeRequest))).thenReturn(describeResponse);

        final SdkHttpClient httpClient = mock(SdkHttpClient.class);
        final ServiceHandlerWrapper wrapper = new ServiceHandlerWrapper(adapter, platformCredentialsProvider,
                                                                        providerLoggingCredentialsProvider,
                                                                        mock(CloudWatchLogPublisher.class),
                                                                        mock(LogPublisher.class), mock(MetricsPublisher.class),
                                                                        mock(MetricsPublisher.class),
                                                                        new CloudWatchScheduler(new CloudWatchEventsProvider(platformCredentialsProvider,
                                                                                                                             httpClient) {
                                                                            @Override
                                                                            public CloudWatchEventsClient get() {
                                                                                return mock(CloudWatchEventsClient.class);
                                                                            }
                                                                        }, loggerProxy, serializer), new Validator(), serializer,
                                                                        client, httpClient);

        wrapper.handleRequest(stream, output, cxt);
        verify(client).createRepository(eq(createRequest));
        verify(client).describeRepository(eq(describeRequest));

        Response<Model> response = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
            new TypeReference<Response<Model>>() {
            });
        assertThat(response).isNotNull();
        assertThat(response.getOperationStatus()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(response.getBearerToken()).isEqualTo("dwezxdfgfgh");
        assertThat(request.getRequestData()).isNotNull();
        Model responseModel = response.getResourceModel();
        assertThat(responseModel.getRepoName()).isEqualTo("repository");
        assertThat(responseModel.getArn()).isNotNull();
        assertThat(responseModel.getCreated()).isNotNull();
    }

    @Order(20)
    @Test
    public void createHandlerAlreadyExists() throws Exception {
        final HandlerRequest<Model, StdCallbackContext> request = prepareRequest();
        final Serializer serializer = new Serializer();
        final InputStream stream = prepareStream(serializer, request);
        final ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        final LoggerProxy loggerProxy = mock(LoggerProxy.class);
        final CredentialsProvider platformCredentialsProvider = prepareMockProvider();
        final CredentialsProvider providerLoggingCredentialsProvider = prepareMockProvider();
        final Context cxt = mock(Context.class);
        // bail out immediately
        when(cxt.getRemainingTimeInMillis()).thenReturn((int) Duration.ofMinutes(1).toMillis());
        when(cxt.getLogger()).thenReturn(mock(LambdaLogger.class));

        final Model model = request.getRequestData().getResourceProperties();
        final ServiceClient client = mock(ServiceClient.class);
        final CreateRequest createRequest = new CreateRequest.Builder().repoName(model.getRepoName()).overrideConfiguration(
            AwsRequestOverrideConfiguration.builder().credentialsProvider(StaticCredentialsProvider.create(MockCreds)).build())
            .build();
        // Now a separate request
        final SdkHttpResponse sdkHttpResponse = mock(SdkHttpResponse.class);
        when(sdkHttpResponse.statusCode()).thenReturn(500);

        final ExistsException exists = new ExistsException(mock(AwsServiceException.Builder.class)) {
            private static final long serialVersionUID = 1L;

            @Override
            public AwsErrorDetails awsErrorDetails() {
                return AwsErrorDetails.builder().errorCode("AlreadyExists").errorMessage("Repo already exists")
                    .sdkHttpResponse(sdkHttpResponse).build();
            }
        };
        when(client.createRepository(eq(createRequest))).thenThrow(exists);

        final SdkHttpClient httpClient = mock(SdkHttpClient.class);
        final ServiceHandlerWrapper wrapper = new ServiceHandlerWrapper(adapter, platformCredentialsProvider,
                                                                        providerLoggingCredentialsProvider,
                                                                        mock(CloudWatchLogPublisher.class),
                                                                        mock(LogPublisher.class), mock(MetricsPublisher.class),
                                                                        mock(MetricsPublisher.class),
                                                                        new CloudWatchScheduler(new CloudWatchEventsProvider(platformCredentialsProvider,
                                                                                                                             httpClient) {
                                                                            @Override
                                                                            public CloudWatchEventsClient get() {
                                                                                return mock(CloudWatchEventsClient.class);
                                                                            }
                                                                        }, loggerProxy, serializer), new Validator(), serializer,
                                                                        client, httpClient);

        wrapper.handleRequest(stream, output, cxt);
        verify(client).createRepository(eq(createRequest));

        Response<Model> response = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
            new TypeReference<Response<Model>>() {
            });
        assertThat(response).isNotNull();
        assertThat(response.getOperationStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(response.getBearerToken()).isEqualTo("dwezxdfgfgh");
        assertThat(request.getRequestData()).isNotNull();
        Model responseModel = response.getResourceModel();
        assertThat(responseModel.getRepoName()).isEqualTo("repository");
        assertThat(response.getMessage()).contains("AlreadyExists");
    }

    @Order(30)
    @Test
    public void createHandlerThottleException() throws Exception {
        final HandlerRequest<Model, StdCallbackContext> request = prepareRequest(Model.builder().repoName("repository").build());
        request.setAction(Action.CREATE);
        final Serializer serializer = new Serializer();
        final InputStream stream = prepareStream(serializer, request);
        ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        final LoggerProxy loggerProxy = mock(LoggerProxy.class);
        final CredentialsProvider platformCredentialsProvider = prepareMockProvider();
        final CredentialsProvider providerLoggingCredentialsProvider = prepareMockProvider();
        final Context cxt = mock(Context.class);
        // bail out very slowly
        when(cxt.getRemainingTimeInMillis()).thenReturn((int) Duration.ofMinutes(1).toMillis());
        when(cxt.getLogger()).thenReturn(mock(LambdaLogger.class));

        final Model model = request.getRequestData().getResourceProperties();
        final ServiceClient client = mock(ServiceClient.class);
        // Now a separate request
        final SdkHttpResponse sdkHttpResponse = mock(SdkHttpResponse.class);
        when(sdkHttpResponse.statusCode()).thenReturn(429);
        final DescribeRequest describeRequest = new DescribeRequest.Builder().repoName(model.getRepoName()).overrideConfiguration(
            AwsRequestOverrideConfiguration.builder().credentialsProvider(StaticCredentialsProvider.create(MockCreds)).build())
            .build();
        final ThrottleException throttleException = new ThrottleException(mock(AwsServiceException.Builder.class)) {
            private static final long serialVersionUID = 1L;

            @Override
            public AwsErrorDetails awsErrorDetails() {
                return AwsErrorDetails.builder().errorCode("ThrottleException").errorMessage("Temporary Limit Exceeded")
                    .sdkHttpResponse(sdkHttpResponse).build();
            }

        };
        when(client.describeRepository(eq(describeRequest))).thenThrow(throttleException);

        final ByteArrayOutputStream cweRequestOutput = new ByteArrayOutputStream(2048);
        CloudWatchEventsClient cloudWatchEventsClient = mock(CloudWatchEventsClient.class);
        when(cloudWatchEventsClient.putRule(any(PutRuleRequest.class))).thenReturn(mock(PutRuleResponse.class));
        when(cloudWatchEventsClient.putTargets(any(PutTargetsRequest.class)))
            .thenAnswer((Answer<PutTargetsResponse>) invocationOnMock -> {
                PutTargetsRequest putTargetsRequest = invocationOnMock.getArgument(0, PutTargetsRequest.class);
                cweRequestOutput.write(putTargetsRequest.targets().get(0).input().getBytes(StandardCharsets.UTF_8));
                return PutTargetsResponse.builder().build();
            });

        final SdkHttpClient httpClient = mock(SdkHttpClient.class);
        final ServiceHandlerWrapper wrapper = new ServiceHandlerWrapper(adapter, platformCredentialsProvider,
                                                                        providerLoggingCredentialsProvider,
                                                                        mock(CloudWatchLogPublisher.class),
                                                                        mock(LogPublisher.class), mock(MetricsPublisher.class),
                                                                        mock(MetricsPublisher.class),
                                                                        new CloudWatchScheduler(new CloudWatchEventsProvider(platformCredentialsProvider,
                                                                                                                             httpClient) {
                                                                            @Override
                                                                            public CloudWatchEventsClient get() {
                                                                                return cloudWatchEventsClient;
                                                                            }
                                                                        }, loggerProxy, serializer), new Validator(), serializer,
                                                                        client, httpClient);

        // Bail early for the handshake. Expect cloudwatch events
        wrapper.handleRequest(stream, output, cxt);
        // Replay Cloudwatch events stream
        // refresh the stream
        output = new ByteArrayOutputStream(2048);
        wrapper.handleRequest(new ByteArrayInputStream(cweRequestOutput.toByteArray()), output, cxt);

        // Handshake mode 1 try, Throttle retries 4 times (1, 0s), (2, 3s), (3, 6s), (4,
        // 9s)
        verify(client, times(5)).describeRepository(eq(describeRequest));
        verify(cloudWatchEventsClient, times(1)).putRule(any(PutRuleRequest.class));
        verify(cloudWatchEventsClient, times(1)).putTargets(any(PutTargetsRequest.class));

        Response<Model> response = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
            new TypeReference<Response<Model>>() {
            });
        assertThat(response).isNotNull();
        assertThat(response.getOperationStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(response.getBearerToken()).isEqualTo("dwezxdfgfgh");
        assertThat(response.getMessage()).contains("Exceeded");
    }

    @Order(40)
    @Test
    public void createHandlerThottleExceptionEarlyInProgressBailout() throws Exception {
        final HandlerRequest<Model, StdCallbackContext> request = prepareRequest(Model.builder().repoName("repository").build());
        request.setAction(Action.CREATE);
        final Serializer serializer = new Serializer();
        final InputStream stream = prepareStream(serializer, request);
        final ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        final LoggerProxy loggerProxy = mock(LoggerProxy.class);
        final CredentialsProvider platformCredentialsProvider = prepareMockProvider();
        final CredentialsProvider providerLoggingCredentialsProvider = prepareMockProvider();
        final Context cxt = mock(Context.class);
        // bail out immediately
        when(cxt.getRemainingTimeInMillis()).thenReturn(50);
        when(cxt.getLogger()).thenReturn(mock(LambdaLogger.class));

        final Model model = request.getRequestData().getResourceProperties();
        final ServiceClient client = mock(ServiceClient.class);
        // Now a separate request
        final SdkHttpResponse sdkHttpResponse = mock(SdkHttpResponse.class);
        when(sdkHttpResponse.statusCode()).thenReturn(429);
        final DescribeRequest describeRequest = new DescribeRequest.Builder().repoName(model.getRepoName()).overrideConfiguration(
            AwsRequestOverrideConfiguration.builder().credentialsProvider(StaticCredentialsProvider.create(MockCreds)).build())
            .build();
        final ThrottleException throttleException = new ThrottleException(mock(AwsServiceException.Builder.class)) {
            private static final long serialVersionUID = 1L;

            @Override
            public AwsErrorDetails awsErrorDetails() {
                return AwsErrorDetails.builder().errorCode("ThrottleException").errorMessage("Temporary Limit Exceeded")
                    .sdkHttpResponse(sdkHttpResponse).build();
            }

        };
        when(client.describeRepository(eq(describeRequest))).thenThrow(throttleException);

        final SdkHttpClient httpClient = mock(SdkHttpClient.class);
        final ServiceHandlerWrapper wrapper = new ServiceHandlerWrapper(adapter, platformCredentialsProvider,
                                                                        providerLoggingCredentialsProvider,
                                                                        mock(CloudWatchLogPublisher.class),
                                                                        mock(LogPublisher.class), mock(MetricsPublisher.class),
                                                                        mock(MetricsPublisher.class),
                                                                        new CloudWatchScheduler(new CloudWatchEventsProvider(platformCredentialsProvider,
                                                                                                                             httpClient) {
                                                                            @Override
                                                                            public CloudWatchEventsClient get() {
                                                                                return mock(CloudWatchEventsClient.class);
                                                                            }
                                                                        }, loggerProxy, serializer), new Validator(), serializer,
                                                                        client, httpClient);

        wrapper.handleRequest(stream, output, cxt);
        // only 1 call (1, 0s), the next attempt is at 3s which exceed 50 ms remaining
        verify(client).describeRepository(eq(describeRequest));

        Response<Model> response = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
            new TypeReference<Response<Model>>() {
            });
        assertThat(response).isNotNull();
        assertThat(response.getOperationStatus()).isEqualTo(OperationStatus.IN_PROGRESS);
        assertThat(response.getBearerToken()).isEqualTo("dwezxdfgfgh");
    }

    @Order(40)
    @Test
    public void accessDenied() throws Exception {
        final HandlerRequest<Model, StdCallbackContext> request = prepareRequest(Model.builder().repoName("repository").build());
        request.setAction(Action.READ);
        final Serializer serializer = new Serializer();
        final InputStream stream = prepareStream(serializer, request);
        final ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        final LoggerProxy loggerProxy = mock(LoggerProxy.class);
        final CredentialsProvider platformCredentialsProvider = prepareMockProvider();
        final CredentialsProvider providerLoggingCredentialsProvider = prepareMockProvider();
        final Context cxt = mock(Context.class);
        // bail out immediately
        when(cxt.getRemainingTimeInMillis()).thenReturn(50);
        when(cxt.getLogger()).thenReturn(mock(LambdaLogger.class));

        final Model model = request.getRequestData().getResourceProperties();
        final ServiceClient client = mock(ServiceClient.class);
        // Now a separate request
        final SdkHttpResponse sdkHttpResponse = mock(SdkHttpResponse.class);
        when(sdkHttpResponse.statusCode()).thenReturn(401);
        final DescribeRequest describeRequest = new DescribeRequest.Builder().repoName(model.getRepoName()).overrideConfiguration(
            AwsRequestOverrideConfiguration.builder().credentialsProvider(StaticCredentialsProvider.create(MockCreds)).build())
            .build();
        final AccessDenied accessDenied = new AccessDenied(mock(AwsServiceException.Builder.class)) {
            private static final long serialVersionUID = 1L;

            @Override
            public AwsErrorDetails awsErrorDetails() {
                return AwsErrorDetails.builder().errorCode("AccessDenied: 401").errorMessage("Token Invalid")
                    .sdkHttpResponse(sdkHttpResponse).build();
            }

        };
        when(client.describeRepository(eq(describeRequest))).thenThrow(accessDenied);

        final SdkHttpClient httpClient = mock(SdkHttpClient.class);
        final ServiceHandlerWrapper wrapper = new ServiceHandlerWrapper(adapter, platformCredentialsProvider,
                                                                        providerLoggingCredentialsProvider,
                                                                        mock(CloudWatchLogPublisher.class),
                                                                        mock(LogPublisher.class), mock(MetricsPublisher.class),
                                                                        mock(MetricsPublisher.class),
                                                                        new CloudWatchScheduler(new CloudWatchEventsProvider(platformCredentialsProvider,
                                                                                                                             httpClient) {
                                                                            @Override
                                                                            public CloudWatchEventsClient get() {
                                                                                return mock(CloudWatchEventsClient.class);
                                                                            }
                                                                        }, loggerProxy, serializer), new Validator(), serializer,
                                                                        client, httpClient);

        wrapper.handleRequest(stream, output, cxt);
        verify(client).describeRepository(eq(describeRequest));

        Response<Model> response = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
            new TypeReference<Response<Model>>() {
            });
        assertThat(response).isNotNull();
        assertThat(response.getOperationStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(response.getBearerToken()).isEqualTo("dwezxdfgfgh");
        assertThat(response.getMessage()).contains("AccessDenied");
    }

}
