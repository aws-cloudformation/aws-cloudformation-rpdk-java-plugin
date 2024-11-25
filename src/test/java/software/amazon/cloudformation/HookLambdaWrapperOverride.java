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

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.json.JSONObject;
import org.json.JSONTokener;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.cloudformation.encryption.Cipher;
import software.amazon.cloudformation.injection.CredentialsProvider;
import software.amazon.cloudformation.loggers.CloudWatchLogPublisher;
import software.amazon.cloudformation.loggers.LogPublisher;
import software.amazon.cloudformation.metrics.MetricsPublisher;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.hook.HookContext;
import software.amazon.cloudformation.proxy.hook.HookHandlerRequest;
import software.amazon.cloudformation.proxy.hook.HookInvocationRequest;
import software.amazon.cloudformation.proxy.hook.targetmodel.HookTargetModel;
import software.amazon.cloudformation.resource.SchemaValidator;
import software.amazon.cloudformation.resource.Serializer;

/**
 * Test class used for testing of HookLambdaWrapper functionality
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HookLambdaWrapperOverride extends HookLambdaWrapper<TestModel, TestContext, TestConfigurationModel> {

    private Map<String, Object> hookInvocationPayloadFromS3;

    /**
     * This .ctor provided for testing
     */
    public HookLambdaWrapperOverride(final CredentialsProvider providerLoggingCredentialsProvider,
                                     final LogPublisher platformEventsLogger,
                                     final CloudWatchLogPublisher providerEventsLogger,
                                     final MetricsPublisher providerMetricsPublisher,
                                     final SchemaValidator validator,
                                     final SdkHttpClient httpClient,
                                     final Cipher cipher,
                                     final Boolean strictDeserialize) {
        super(providerLoggingCredentialsProvider, providerEventsLogger, platformEventsLogger, providerMetricsPublisher, validator,
              new Serializer(strictDeserialize), httpClient, cipher);
    }

    @Override
    protected JSONObject provideHookSchemaJSONObject() {
        return new JSONObject(new JSONTokener(new ByteArrayInputStream("{ \"properties\": { \"property1\": { \"type\": \"string\" }, \"property2\": { \"type\": \"integer\" } } }"
            .getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public ProgressEvent<TestModel, TestContext> invokeHandler(final AmazonWebServicesClientProxy awsClientProxy,
                                                               final HookHandlerRequest request,
                                                               final HookInvocationPoint invocationPoint,
                                                               final TestContext callbackContext,
                                                               final TestConfigurationModel typeConfiguration)
        throws Exception {
        this.awsClientProxy = awsClientProxy;
        this.request = request;
        this.invocationPoint = invocationPoint;
        this.callbackContext = callbackContext;

        if (invokeHandlerException != null) {
            throw invokeHandlerException;
        } else if (invokeHandlerResponses == null) {
            return invokeHandlerResponse;
        } else {
            return invokeHandlerResponses.remove();
        }

    }

    // lets tests assert on the passed in arguments
    public AmazonWebServicesClientProxy awsClientProxy;
    public HookHandlerRequest request;
    public HookInvocationPoint invocationPoint;
    public TestContext callbackContext;

    // allows test to have the invoke throw an exception
    public Exception invokeHandlerException;

    // for single mocked response
    public ProgressEvent<TestModel, TestContext> invokeHandlerResponse;

    // for series of mocked responses; call EnqueueResponses to add items
    private Queue<ProgressEvent<TestModel, TestContext>> invokeHandlerResponses;

    public void enqueueResponses(final List<ProgressEvent<TestModel, TestContext>> responses) {
        if (invokeHandlerResponses == null) {
            invokeHandlerResponses = new LinkedList<>();
        }

        invokeHandlerResponses.addAll(responses);
    }

    @Override
    protected HookHandlerRequest transform(final HookInvocationRequest<TestConfigurationModel, TestContext> request) {
        this.request = HookHandlerRequest.builder().clientRequestToken(request.getClientRequestToken())
            .hookContext(HookContext.builder().awsAccountId(request.getAwsAccountId()).stackId(request.getStackId())
                .changeSetId(request.getChangeSetId()).hookTypeName(request.getHookTypeName())
                .hookTypeVersion(request.getHookTypeVersion()).invocationPoint(request.getActionInvocationPoint())
                .targetName(request.getRequestData().getTargetName()).targetType(request.getRequestData().getTargetType())
                .targetLogicalId(request.getRequestData().getTargetLogicalId())
                .targetModel(HookTargetModel.of(request.getRequestData().getTargetModel())).build())
            .build();

        return this.request;
    }

    public HookHandlerRequest transformResponse;

    @Override
    public Map<String, Object> retrieveHookInvocationPayloadFromS3(final String s3PresignedUrl) {
        return hookInvocationPayloadFromS3;
    }

    public void setHookInvocationPayloadFromS3(Map<String, Object> input) {
        hookInvocationPayloadFromS3 = input;
    }

    @Override
    protected TypeReference<HookInvocationRequest<TestConfigurationModel, TestContext>> getTypeReference() {
        return new TypeReference<HookInvocationRequest<TestConfigurationModel, TestContext>>() {
        };
    }

    @Override
    protected TypeReference<TestConfigurationModel> getModelTypeReference() {
        return new TypeReference<TestConfigurationModel>() {
        };
    }

    @Override
    protected void scrubFiles() {
    }
}
