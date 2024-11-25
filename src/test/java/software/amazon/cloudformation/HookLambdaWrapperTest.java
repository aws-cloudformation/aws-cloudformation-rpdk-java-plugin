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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.cloudformation.encryption.KMSCipher;
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
import software.amazon.cloudformation.proxy.hook.HookRequestData;
import software.amazon.cloudformation.proxy.hook.HookStatus;
import software.amazon.cloudformation.proxy.hook.targetmodel.ChangedResource;
import software.amazon.cloudformation.proxy.hook.targetmodel.StackHookTargetModel;
import software.amazon.cloudformation.resource.SchemaValidator;
import software.amazon.cloudformation.resource.Serializer;

@ExtendWith(MockitoExtension.class)
public class HookLambdaWrapperTest {

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
    private LambdaLogger lambdaLogger;

    @Mock
    private HookHandlerRequest hookHandlerRequest;

    @Mock
    private SdkHttpClient httpClient;

    @Mock
    private KMSCipher cipher;

    private HookLambdaWrapperOverride wrapper;
    private HookLambdaWrapperOverride wrapperStrictDeserialize;

    @BeforeEach
    public void initWrapper() {
        wrapper = new HookLambdaWrapperOverride(providerLoggingCredentialsProvider, platformEventsLogger, providerEventsLogger,
                                                providerMetricsPublisher, validator, httpClient, cipher, false);

        wrapperStrictDeserialize = new HookLambdaWrapperOverride(providerLoggingCredentialsProvider, platformEventsLogger,
                                                                 providerEventsLogger, providerMetricsPublisher, validator,
                                                                 httpClient, cipher, true);
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

    private Context getLambdaContext() {
        final Context context = mock(Context.class);
        lenient().when(context.getInvokedFunctionArn())
            .thenReturn("arn:aws:lambda:aws-region:acct-id:function:testHookHandler:PROD");
        lenient().when(context.getLogger()).thenReturn(lambdaLogger);
        return context;
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
        assertThat(handlerResponse.getMessage()).isEqualTo(expected.getMessage());
        assertThat(handlerResponse.getResult()).isEqualTo(expected.getResult());
        assertThat(handlerResponse.getCallbackContext()).isEqualTo(expected.getCallbackContext());
        assertThat(handlerResponse.getCallbackDelaySeconds()).isEqualTo(expected.getCallbackDelaySeconds());
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
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").hookStatus(HookStatus.SUCCESS).build());

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.invocationPoint).isEqualTo(invocationPoint);
            assertThat(wrapper.callbackContext).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request.with-resource-properties.json,CREATE_PRE_PROVISION" })
    public void invokeHandler_WithResourceProperties_returnsSuccess(final String requestDataPath,
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
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").hookStatus(HookStatus.SUCCESS).build());

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.invocationPoint).isEqualTo(invocationPoint);
            assertThat(wrapper.callbackContext).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request.with-resource-properties-and-extraneous-fields.json,CREATE_PRE_PROVISION" })
    public void invokeHandler_WithResourcePropertiesAndExtraneousFields_returnsSuccess(final String requestDataPath,
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
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").hookStatus(HookStatus.SUCCESS).build());

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.invocationPoint).isEqualTo(invocationPoint);
            assertThat(wrapper.callbackContext).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request.with-resource-properties.json,CREATE_PRE_PROVISION" })
    public void invokeHandler_StrictDeserializer_WithResourceProperties_returnsSuccess(final String requestDataPath,
                                                                                       final String invocationPointString)
        throws IOException {
        final HookInvocationPoint invocationPoint = HookInvocationPoint.valueOf(invocationPointString);

        // if the handler responds Complete, this is treated as a successful synchronous
        // completion
        final ProgressEvent<TestModel,
            TestContext> pe = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.SUCCESS).build();
        wrapperStrictDeserialize.setInvokeHandlerResponse(pe);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        wrapperStrictDeserialize.setTransformResponse(hookHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapperStrictDeserialize.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").hookStatus(HookStatus.SUCCESS).build());

            // assert handler receives correct injections
            assertThat(wrapperStrictDeserialize.awsClientProxy).isNotNull();
            assertThat(wrapperStrictDeserialize.invocationPoint).isEqualTo(invocationPoint);
            assertThat(wrapperStrictDeserialize.callbackContext).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request.with-resource-properties-and-extraneous-fields.json" })
    public void
        invokeHandler_StrictDeserializer_WithResourcePropertiesAndExtraneousFields_returnsFailure(final String requestDataPath)
            throws IOException {
        // if the handler responds Complete, this is treated as a successful synchronous
        // completion
        final ProgressEvent<TestModel,
            TestContext> pe = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.SUCCESS).build();
        wrapperStrictDeserialize.setInvokeHandlerResponse(pe);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        wrapperStrictDeserialize.setTransformResponse(hookHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapperStrictDeserialize.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verify(providerLoggingCredentialsProvider, times(0)).setCredentials(any(Credentials.class));
            verify(providerMetricsPublisher, times(0)).refreshClient();

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken(null).hookStatus(HookStatus.FAILED)
                    .errorCode(HandlerErrorCode.InternalFailure).callbackContext(null)
                    .message(expectedStringWhenStrictDeserializingWithExtraneousFields).build());

            // assert handler receives correct injections
            assertThat(wrapperStrictDeserialize.awsClientProxy).isNull();
            assertThat(wrapperStrictDeserialize.getRequest()).isEqualTo(null);
            assertThat(wrapperStrictDeserialize.invocationPoint).isEqualTo(null);
            assertThat(wrapperStrictDeserialize.callbackContext).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({ "preCreate.request.with-stack-level-hook.json,CREATE_PRE_PROVISION" })
    public void invokeHandler_WithStackLevelHook_returnsSuccess(final String requestDataPath, final String invocationPointString)
        throws IOException {
        final HookInvocationPoint invocationPoint = HookInvocationPoint.valueOf(invocationPointString);

        final ProgressEvent<TestModel,
            TestContext> pe = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.SUCCESS).build();
        wrapper.setInvokeHandlerResponse(pe);

        lenient().when(cipher.decryptCredentials(any())).thenReturn(new Credentials("123", "123", "123"));

        wrapper.setHookInvocationPayloadFromS3(Map.of(
                "Template", "template string here",
                "PreviousTemplate", "previous template string here",
                "ResolvedTemplate", "resolved template string here",
                "ChangedResources", List.of(
                        Map.of(
                                "LogicalResourceId", "SomeLogicalResourceId",
                                "ResourceType", "AWS::S3::Bucket",
                                "Action", "CREATE",
                                "LineNumber", 3,
                                "ResourceProperties", "<Resource Properties as json string>",
                                "PreviousResourceProperties", "<Resource Properties as json string>"
                        )
                )
        ));

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // verify output response
            verifyHandlerResponse(out,
                HookProgressEvent.<TestContext>builder().clientRequestToken("123456").hookStatus(HookStatus.SUCCESS).build());

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.invocationPoint).isEqualTo(invocationPoint);
            assertThat(wrapper.callbackContext).isNull();

            assertThat(wrapper.getRequest().getHookContext().getTargetType()).isEqualTo("STACK");
            assertThat(wrapper.getRequest().getHookContext().getTargetName()).isEqualTo("STACK");
            assertThat(wrapper.getRequest().getHookContext().getTargetLogicalId()).isEqualTo("myStack");

            StackHookTargetModel stackHookTargetModel = wrapper.getRequest().getHookContext()
                .getTargetModel(StackHookTargetModel.class);
            assertThat(stackHookTargetModel.getTemplate()).isEqualTo("template string here");
            assertThat(stackHookTargetModel.getPreviousTemplate()).isEqualTo("previous template string here");
            assertThat(stackHookTargetModel.getResolvedTemplate()).isEqualTo("resolved template string here");
            assertThat(stackHookTargetModel.getChangedResources().size()).isEqualTo(1);

            ChangedResource expectedChangedResource = ChangedResource.builder().logicalResourceId("SomeLogicalResourceId")
                .resourceType("AWS::S3::Bucket").lineNumber(3).action("CREATE")
                .resourceProperties("<Resource Properties as json string>")
                .previousResourceProperties("<Resource Properties as json string>").build();
            assertThat(stackHookTargetModel.getChangedResources().get(0)).isEqualTo(expectedChangedResource);
        }
    }

     @Test
     public void testIsHookInvocationPayloadRemote() {
         List<HookRequestData> invalidHookRequestDataObjects = ImmutableList.of(
             HookRequestData.builder().targetModel(null).build(),
             HookRequestData.builder().targetModel(null).payload(null).build(),
             HookRequestData.builder().targetModel(Collections.emptyMap()).payload(null).build()
         );

         invalidHookRequestDataObjects.forEach(requestData -> {
            Assertions.assertThrows(TerminalException.class, () -> wrapper.isHookInvocationPayloadRemote(requestData));
         });

         Assertions.assertThrows(TerminalException.class, () -> wrapper.isHookInvocationPayloadRemote(null));

         HookRequestData bothFieldsPopulated = HookRequestData.builder()
                 .targetModel(ImmutableMap.of("foo", "bar"))
                 .payload("http://s3PresignedUrl")
                 .build();
         HookRequestData onlyTargetModelPopulated = HookRequestData.builder()
                 .targetModel(ImmutableMap.of("foo", "bar"))
                 .payload(null).build();
         HookRequestData onlyPayloadPopulated = HookRequestData.builder()
                 .targetModel(Collections.emptyMap())
                 .payload("http://s3PresignedUrl").build();
         HookRequestData onlyPayloadPopulatedWithNullTargetModel = HookRequestData.builder().targetModel(null)
                 .payload("http://s3PresignedUrl").build();

         Assertions.assertFalse(wrapper.isHookInvocationPayloadRemote(bothFieldsPopulated));
         Assertions.assertFalse(wrapper.isHookInvocationPayloadRemote(onlyTargetModelPopulated));
         Assertions.assertTrue(wrapper.isHookInvocationPayloadRemote(onlyPayloadPopulated));
         Assertions.assertTrue(wrapper.isHookInvocationPayloadRemote(onlyPayloadPopulatedWithNullTargetModel));
     }

    private final String expectedStringWhenStrictDeserializingWithExtraneousFields = "Unrecognized field \"targetName\" (class software.amazon.cloudformation.proxy.hook.HookInvocationRequest), not marked as ignorable (10 known properties: \"requestContext\", \"stackId\", \"clientRequestToken\", \"hookModel\", \"hookTypeName\", \"requestData\", \"actionInvocationPoint\", \"awsAccountId\", \"changeSetId\", \"hookTypeVersion\"])\n"
        + " at [Source: (String)\"{\n" + "    \"clientRequestToken\": \"123456\",\n" + "    \"awsAccountId\": \"123456789012\",\n"
        + "    \"stackId\": \"arn:aws:cloudformation:us-east-1:123456789012:stack/SampleStack/e722ae60-fe62-11e8-9a0e-0ae8cc519968\",\n"
        + "    \"changeSetId\": \"arn:aws:cloudformation:us-east-1:123456789012:changeSet/SampleChangeSet-conditional/1a2345b6-0000-00a0-a123-00abc0abc000\",\n"
        + "    \"hookTypeName\": \"AWS::Test::TestModel\",\n" + "    \"hookTypeVersion\": \"1.0\",\n" + "    \"hookModel\": {\n"
        + "        \"property1\": \"abc\",\n" + "        \"property2\": 123\n" + "    },\n"
        + "    \"action\"[truncated 1935 chars]; line: 40, column: 20] (through reference chain: software.amazon.cloudformation.proxy.hook.HookInvocationRequest[\"targetName\"])";
}
