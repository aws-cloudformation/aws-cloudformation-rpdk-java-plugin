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
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.cloudformation.Action;
import software.amazon.cloudformation.injection.CredentialsProvider;
import software.amazon.cloudformation.loggers.CloudWatchLogPublisher;
import software.amazon.cloudformation.loggers.LogPublisher;
import software.amazon.cloudformation.metrics.MetricsPublisher;
import software.amazon.cloudformation.proxy.handler.Model;
import software.amazon.cloudformation.proxy.handler.ServiceHandlerWrapper;
import software.amazon.cloudformation.proxy.handler.TypeConfigurationModel;
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

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class End2EndCallChainTest {
    //
    // The same that is asserted inside the ServiceClient
    //
    private final AwsSessionCredentials MockCreds = AwsSessionCredentials.create("accessKeyId", "secretKey", "token");
    private final Credentials credentials = new Credentials(MockCreds.accessKeyId(), MockCreds.secretAccessKey(),
                                                            MockCreds.sessionToken());

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
                .translateToServiceRequest((m) -> new CreateRequest.Builder().repoName(m.getRepoName()).build())
                .makeServiceCall((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::createRepository))
                .done(r -> ProgressEvent.success(model, context));

        assertThat(event.getStatus()).isEqualTo(OperationStatus.SUCCESS);

        // replay, should get the same result.
        event = proxy.initiate("client:createRepository", client, model, context)
            .translateToServiceRequest((m) -> new CreateRequest.Builder().repoName(m.getRepoName()).build())
            .makeServiceCall((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::createRepository))
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
        final ExistsException exists = new ExistsException(new AwsServiceException(AwsServiceException.builder()) {
            private static final long serialVersionUID = 1L;

            @Override
            public AwsErrorDetails awsErrorDetails() {
                return AwsErrorDetails.builder().errorCode("AlreadyExists").errorMessage("Repo already exists")
                    .sdkHttpResponse(sdkHttpResponse).build();
            }
        }.toBuilder());
        when(serviceClient.createRepository(any(CreateRequest.class))).thenThrow(exists);
        StdCallbackContext newContext = new StdCallbackContext();
        event = proxy.initiate("client:createRepository", client, model, newContext)
            .translateToServiceRequest((m) -> new CreateRequest.Builder().repoName(m.getRepoName()).build())
            .makeServiceCall((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::createRepository))
            .done(r -> ProgressEvent.success(model, context));

        assertThat(event.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(event.getMessage()).contains("Repo already exists");
    }

    private HandlerRequest<Model, StdCallbackContext, TypeConfigurationModel> prepareRequest(Model model) throws Exception {
        HandlerRequest<Model, StdCallbackContext, TypeConfigurationModel> request = new HandlerRequest<>();
        request.setAction(Action.CREATE);
        request.setAwsAccountId("1234567891234");
        request.setBearerToken("dwezxdfgfgh");
        request.setNextToken(null);
        request.setRegion("us-east-2");
        request.setResourceType("AWS::Code::Repository");
        request.setStackId(UUID.randomUUID().toString());
        RequestData<Model, TypeConfigurationModel> data = new RequestData<>();
        data.setResourceProperties(model);
        data.setCallerCredentials(credentials);
        request.setRequestData(data);
        return request;
    }

    private HandlerRequest<Model, StdCallbackContext, TypeConfigurationModel> prepareRequest() throws Exception {
        return prepareRequest(Model.builder().repoName("repository").build());
    }

    private InputStream prepareStream(Serializer serializer,
                                      HandlerRequest<Model, StdCallbackContext, TypeConfigurationModel> request)
        throws Exception {

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

            @Override
            public void resetCredentials() {

            }
        };
    }

    @Order(5)
    @Test
    public void notFound() throws Exception {
        final HandlerRequest<Model, StdCallbackContext,
            TypeConfigurationModel> request = prepareRequest(Model.builder().repoName("repository").build());
        request.setAction(Action.READ);
        final Model model = request.getRequestData().getResourceProperties();
        final Serializer serializer = new Serializer();
        final InputStream stream = prepareStream(serializer, request);
        final ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        final LoggerProxy loggerProxy = mock(LoggerProxy.class);
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
        final NotFoundException notFound = new NotFoundException(new AwsServiceException(AwsServiceException.builder()) {
            private static final long serialVersionUID = 1L;

            @Override
            public AwsErrorDetails awsErrorDetails() {
                return AwsErrorDetails.builder().errorCode("NotFound").errorMessage("Repo not existing")
                    .sdkHttpResponse(sdkHttpResponse).build();
            }
        }.toBuilder());
        when(client.describeRepository(eq(describeRequest))).thenThrow(notFound);

        final SdkHttpClient httpClient = mock(SdkHttpClient.class);
        final ServiceHandlerWrapper wrapper = new ServiceHandlerWrapper(providerLoggingCredentialsProvider,
                                                                        mock(CloudWatchLogPublisher.class),
                                                                        mock(LogPublisher.class), mock(MetricsPublisher.class),
                                                                        new Validator(), serializer, client, httpClient);

        wrapper.processRequest(stream, output);
        verify(client).describeRepository(eq(describeRequest));

        ProgressEvent<Model, StdCallbackContext> response = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
            new TypeReference<ProgressEvent<Model, StdCallbackContext>>() {
            });
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(response.getMessage()).contains("Repo not existing");
    }

    @SuppressWarnings("unchecked")
    @Order(10)
    @Test
    public void createHandler() throws Exception {
        final HandlerRequest<Model, StdCallbackContext, TypeConfigurationModel> request = prepareRequest();
        final Serializer serializer = new Serializer();
        final InputStream stream = prepareStream(serializer, request);
        final ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        final LoggerProxy loggerProxy = mock(LoggerProxy.class);
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
        final ServiceHandlerWrapper wrapper = new ServiceHandlerWrapper(providerLoggingCredentialsProvider,
                                                                        mock(CloudWatchLogPublisher.class),
                                                                        mock(LogPublisher.class), mock(MetricsPublisher.class),
                                                                        new Validator(), serializer, client, httpClient);

        wrapper.processRequest(stream, output);
        verify(client).createRepository(eq(createRequest));
        verify(client).describeRepository(eq(describeRequest));

        ProgressEvent<Model, StdCallbackContext> response = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
            new TypeReference<ProgressEvent<Model, StdCallbackContext>>() {
            });
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(request.getRequestData()).isNotNull();
        Model responseModel = response.getResourceModel();
        assertThat(responseModel.getRepoName()).isEqualTo("repository");
        assertThat(responseModel.getArn()).isNotNull();
        assertThat(responseModel.getCreated()).isNotNull();
    }

    @Order(20)
    @Test
    public void createHandlerAlreadyExists() throws Exception {
        final HandlerRequest<Model, StdCallbackContext, TypeConfigurationModel> request = prepareRequest();
        final Serializer serializer = new Serializer();
        final InputStream stream = prepareStream(serializer, request);
        final ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
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

        final ExistsException exists = new ExistsException(new AwsServiceException(AwsServiceException.builder()) {
            private static final long serialVersionUID = 1L;

            @Override
            public AwsErrorDetails awsErrorDetails() {
                return AwsErrorDetails.builder().errorCode("AlreadyExists").errorMessage("Repo already exists")
                    .sdkHttpResponse(sdkHttpResponse).build();
            }
        }.toBuilder());
        when(client.createRepository(eq(createRequest))).thenThrow(exists);

        final SdkHttpClient httpClient = mock(SdkHttpClient.class);
        final ServiceHandlerWrapper wrapper = new ServiceHandlerWrapper(providerLoggingCredentialsProvider,
                                                                        mock(CloudWatchLogPublisher.class),
                                                                        mock(LogPublisher.class), mock(MetricsPublisher.class),
                                                                        new Validator(), serializer, client, httpClient);

        wrapper.processRequest(stream, output);
        verify(client).createRepository(eq(createRequest));

        ProgressEvent<Model, StdCallbackContext> response = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
            new TypeReference<ProgressEvent<Model, StdCallbackContext>>() {
            });
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(request.getRequestData()).isNotNull();
        Model responseModel = response.getResourceModel();
        assertThat(responseModel.getRepoName()).isEqualTo("repository");
        assertThat(response.getMessage()).contains("Repo already exists");
    }

    @Order(30)
    @Test
    public void createHandlerThrottleException() throws Exception {
        HandlerRequest<Model, StdCallbackContext,
            TypeConfigurationModel> request = prepareRequest(Model.builder().repoName("repository").build());
        request.setAction(Action.CREATE);
        final Serializer serializer = new Serializer();
        final InputStream stream = prepareStream(serializer, request);
        ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        final LoggerProxy loggerProxy = mock(LoggerProxy.class);
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
        when(client.createRepository(any())).thenReturn(mock(CreateResponse.class));

        final SdkHttpClient httpClient = mock(SdkHttpClient.class);
        final ServiceHandlerWrapper wrapper = new ServiceHandlerWrapper(providerLoggingCredentialsProvider,
                                                                        mock(CloudWatchLogPublisher.class),
                                                                        mock(LogPublisher.class), mock(MetricsPublisher.class),
                                                                        new Validator(), serializer, client, httpClient);

        ProgressEvent<Model, StdCallbackContext> progress;
        do {
            output = new ByteArrayOutputStream(2048);
            wrapper.processRequest(prepareStream(serializer, request), output);
            progress = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
                new TypeReference<ProgressEvent<Model, StdCallbackContext>>() {
                });
            request = prepareRequest(progress.getResourceModel());
            request.setCallbackContext(progress.getCallbackContext());
        } while (progress.isInProgressCallbackDelay());

        // Throttle retries 4 times (1, 0s), (2, 3s), (3, 6s), (4, 9s)
        verify(client, times(4)).describeRepository(eq(describeRequest));

        ProgressEvent<Model, StdCallbackContext> response = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
            new TypeReference<ProgressEvent<Model, StdCallbackContext>>() {
            });
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(response.getMessage()).contains("Exceeded");
    }

    @Order(40)
    @Test
    public void createHandlerThottleExceptionEarlyInProgressBailout() throws Exception {
        final HandlerRequest<Model, StdCallbackContext,
            TypeConfigurationModel> request = prepareRequest(Model.builder().repoName("repository").build());
        request.setAction(Action.CREATE);
        final Serializer serializer = new Serializer();
        final InputStream stream = prepareStream(serializer, request);
        final ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        final LoggerProxy loggerProxy = mock(LoggerProxy.class);
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
        final ServiceHandlerWrapper wrapper = new ServiceHandlerWrapper(providerLoggingCredentialsProvider,
                                                                        mock(CloudWatchLogPublisher.class),
                                                                        mock(LogPublisher.class), mock(MetricsPublisher.class),
                                                                        new Validator(), serializer, client, httpClient);

        wrapper.processRequest(stream, output);
        // only 1 call (1, 0s), the next attempt is at 3s which exceed 50 ms remaining
        verify(client).describeRepository(eq(describeRequest));

        ProgressEvent<Model, StdCallbackContext> response = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
            new TypeReference<ProgressEvent<Model, StdCallbackContext>>() {
            });
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OperationStatus.IN_PROGRESS);
    }

    @Order(40)
    @Test
    public void accessDenied() throws Exception {
        final HandlerRequest<Model, StdCallbackContext,
            TypeConfigurationModel> request = prepareRequest(Model.builder().repoName("repository").build());
        request.setAction(Action.READ);
        final Serializer serializer = new Serializer();
        final InputStream stream = prepareStream(serializer, request);
        final ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        final LoggerProxy loggerProxy = mock(LoggerProxy.class);
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
        final AccessDenied accessDenied = new AccessDenied(new AwsServiceException(AwsServiceException.builder()) {
            private static final long serialVersionUID = 1L;

            @Override
            public AwsErrorDetails awsErrorDetails() {
                return AwsErrorDetails.builder().errorCode("AccessDenied: 401").errorMessage("Token Invalid")
                    .sdkHttpResponse(sdkHttpResponse).build();
            }

        }.toBuilder());
        when(client.describeRepository(eq(describeRequest))).thenThrow(accessDenied);

        final SdkHttpClient httpClient = mock(SdkHttpClient.class);
        final ServiceHandlerWrapper wrapper = new ServiceHandlerWrapper(providerLoggingCredentialsProvider,
                                                                        mock(CloudWatchLogPublisher.class),
                                                                        mock(LogPublisher.class), mock(MetricsPublisher.class),
                                                                        new Validator(), serializer, client, httpClient);

        wrapper.processRequest(stream, output);
        verify(client).describeRepository(eq(describeRequest));

        ProgressEvent<Model, StdCallbackContext> response = serializer.deserialize(output.toString(StandardCharsets.UTF_8.name()),
            new TypeReference<ProgressEvent<Model, StdCallbackContext>>() {
            });
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(response.getMessage()).contains("Token Invalid");
    }

}
