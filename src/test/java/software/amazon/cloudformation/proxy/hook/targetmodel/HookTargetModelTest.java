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
package software.amazon.cloudformation.proxy.hook.targetmodel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HookTargetModelTest {
    private static final String TEST_DATA_BASE_PATH = "src/test/java/software/amazon/cloudformation/data/hook/%s";
    private static final String RESOURCE_PROPERTIES_KEY = "ResourceProperties";
    private static final String PREVIOUS_RESOURCE_PROPERTIES_KEY = "PreviousResourceProperties";
    private static final String TEST_PROPERTY_1_KEY = "Property1";
    private static final String TEST_PROPERTY_1 = "property";
    private static final String UPDATED_TEST_PROPERTY_1 = "updated_property";
    private static final String TEST_PROPERTY_2_KEY = "Property2";
    private static final Integer TEST_PROPERTY_2 = 21;
    private static final String TEST_TAGS_KEY = "Tags";
    private static final Map<String, String> TEST_TAGS = ImmutableMap.of("Key", "Value");

    private static final Map<String, Object> TEST_RESOURCE_PROPERTIES_MAP = ImmutableMap.of(TEST_PROPERTY_1_KEY, TEST_PROPERTY_1,
        TEST_PROPERTY_2_KEY, TEST_PROPERTY_2, TEST_TAGS_KEY, TEST_TAGS);
    private static final Map<String, Object> UPDATED_TEST_RESOURCE_PROPERTIES_MAP = ImmutableMap.of(TEST_PROPERTY_1_KEY,
        UPDATED_TEST_PROPERTY_1, TEST_PROPERTY_2_KEY, TEST_PROPERTY_2, TEST_TAGS_KEY, TEST_TAGS);

    private static final TestResource TEST_RESOURCE = new TestResource(TEST_PROPERTY_1, TEST_PROPERTY_2, TEST_TAGS);
    private static final TestResource UPDATED_TEST_RESOURCE = new TestResource(UPDATED_TEST_PROPERTY_1, TEST_PROPERTY_2,
                                                                               TEST_TAGS);

    private static final Map<String,
        Object> TEST_TARGET_MODEL_MAP = ImmutableMap.of(RESOURCE_PROPERTIES_KEY, TEST_RESOURCE_PROPERTIES_MAP);
    private static final Map<String, Object> UPDATED_TEST_TARGET_MODEL_MAP = ImmutableMap.of(RESOURCE_PROPERTIES_KEY,
        UPDATED_TEST_RESOURCE_PROPERTIES_MAP, PREVIOUS_RESOURCE_PROPERTIES_KEY, TEST_RESOURCE_PROPERTIES_MAP);

    private static final TestResourceHookTargetModel TEST_TARGET_MODEL = new TestResourceHookTargetModel(TEST_RESOURCE);
    private static final TestResourceHookTargetModel UPDATED_TARGET_MODEL = new TestResourceHookTargetModel(UPDATED_TEST_RESOURCE,
                                                                                                            TEST_RESOURCE);
    private static final String TEST_TARGET_MODEL_JSON = "{\"ResourceProperties\":{\"Property1\":\"property\",\"Property2\":21,\"Tags\":{\"Key\":\"Value\"}},\"PreviousResourceProperties\":null}";
    private static final String UPDATED_TEST_TARGET_MODEL_JSON = "{\"ResourceProperties\":{\"Property1\":\"updated_property\",\"Property2\":21,\"Tags\":{\"Key\":\"Value\"}},\"PreviousResourceProperties\":{\"Property1\":\"property\",\"Property2\":21,\"Tags\":{\"Key\":\"Value\"}}}";

    private static final TypeReference<
        TestResourceHookTargetModel> TEST_TYPE_REFERENCE = new TypeReference<TestResourceHookTargetModel>() {
        };

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void testHookTargetModel() {
        final HookTargetModel targetModel = HookTargetModel.of(TEST_TARGET_MODEL_MAP);
        Assertions.assertNotNull(targetModel);
        Assertions.assertTrue(targetModel.containsKey(RESOURCE_PROPERTIES_KEY));
        Assertions.assertEquals(TEST_RESOURCE_PROPERTIES_MAP, targetModel.get(RESOURCE_PROPERTIES_KEY));
    }

    @Test
    public void testHookTargetModel_forUpdateHookTarget() {
        final HookTargetModel targetModel = HookTargetModel.of(UPDATED_TEST_TARGET_MODEL_MAP);
        Assertions.assertNotNull(targetModel);
        Assertions.assertEquals(UPDATED_TEST_RESOURCE_PROPERTIES_MAP, targetModel.get(RESOURCE_PROPERTIES_KEY));
        Assertions.assertEquals(TEST_RESOURCE_PROPERTIES_MAP, targetModel.get(PREVIOUS_RESOURCE_PROPERTIES_KEY));
    }

    @Test
    public void testHookTargetModel_fromJsonObject() {
        final HookTargetModel targetModel = HookTargetModel.of(new JSONObject(TEST_TARGET_MODEL_MAP));
        Assertions.assertNotNull(targetModel);
        Assertions.assertTrue(targetModel.containsKey(RESOURCE_PROPERTIES_KEY));
        Assertions.assertEquals(TEST_RESOURCE_PROPERTIES_MAP, targetModel.get(RESOURCE_PROPERTIES_KEY));
    }

    @Test
    public void testHookTargetModel_getOrDefault() {
        final HookTargetModel targetModel = HookTargetModel.of(new JSONObject(TEST_TARGET_MODEL_MAP));
        Assertions.assertNotNull(targetModel);
        Assertions.assertTrue(targetModel.containsKey(RESOURCE_PROPERTIES_KEY));
        Assertions.assertEquals(TEST_RESOURCE_PROPERTIES_MAP, targetModel.get(RESOURCE_PROPERTIES_KEY));
        Assertions.assertNull(targetModel.get(PREVIOUS_RESOURCE_PROPERTIES_KEY));
        Assertions.assertEquals(UPDATED_TEST_RESOURCE_PROPERTIES_MAP,
            targetModel.getOrDefault(PREVIOUS_RESOURCE_PROPERTIES_KEY, UPDATED_TEST_RESOURCE_PROPERTIES_MAP));
    }

    @Test
    public void testHookTarget_getOrDefault() {
        final TestResourceHookTargetModel targetModel = HookTargetModel.of(TEST_TARGET_MODEL_MAP,
            TestResourceHookTargetModel.class);

        final TestResource target = targetModel.getResourceProperties();
        Assertions.assertNotNull(target);
        Assertions.assertTrue(target.hasProperty(TEST_PROPERTY_1_KEY));
        Assertions.assertEquals(TEST_PROPERTY_1, target.getProperty1());
        Assertions.assertEquals(TEST_PROPERTY_1, target.get(TEST_PROPERTY_1_KEY));

        final String nonExistKey = "DoNotExistKey";
        final String nonExistValue = "DoNotExistValue";
        Assertions.assertFalse(target.hasProperty(nonExistKey));
        Assertions.assertNull(target.get(nonExistKey));
        Assertions.assertEquals(nonExistValue, target.getOrDefault(nonExistKey, nonExistValue));
    }

    @Test
    public void testGeneralHookTargetModel() {
        final Map<String, Object> genericTarget = ImmutableMap.of("TargetProperty1", "TargetPropertyVal");
        final Map<String, Object> genericTargetModel = ImmutableMap.of("TargetProperties", genericTarget);

        final HookTargetModel targetModel = HookTargetModel.of(genericTargetModel);
        Assertions.assertNotNull(targetModel);
        Assertions.assertEquals(genericTargetModel, targetModel.getTargetModel());
        Assertions.assertEquals(genericTarget, targetModel.get("TargetProperties"));
    }

    @Test
    public void testTypedResourceHookTargetModel() {
        final TestResourceHookTargetModel targetModel = HookTargetModel.of(TEST_TARGET_MODEL_MAP,
            TestResourceHookTargetModel.class);
        assertHookTargetProperties(targetModel);
    }

    @Test
    public void testTypedResourceHookTargetModel_forUpdateHookTarget() {
        final TestResourceHookTargetModel targetModel = HookTargetModel.of(UPDATED_TEST_TARGET_MODEL_MAP,
            TestResourceHookTargetModel.class);
        assertHookTargetProperties_forUpdateHook(targetModel);
    }

    @Test
    public void testTypedResourceHookTargetModel_usingTypeReference() {
        final TestResourceHookTargetModel targetModel = HookTargetModel.of(TEST_TARGET_MODEL_MAP, TEST_TYPE_REFERENCE);
        assertHookTargetProperties(targetModel);
    }

    @Test
    public void testTypedResourceHookTargetModel_forUpdateHookTarget_usingTypeReference() {
        final TestResourceHookTargetModel targetModel = HookTargetModel.of(UPDATED_TEST_TARGET_MODEL_MAP, TEST_TYPE_REFERENCE);
        assertHookTargetProperties_forUpdateHook(targetModel);
    }

    @Test
    public void testTypedResourceHookTargetModel_fromJson() {
        final TestResourceHookTargetModel targetModel = HookTargetModel.of(TEST_TARGET_MODEL_JSON, TEST_TYPE_REFERENCE);
        assertHookTargetProperties(targetModel);
    }

    @Test
    public void testTypedResourceHookTargetModel_forUpdateHookTarget_fromJson() {
        final TestResourceHookTargetModel targetModel = HookTargetModel.of(UPDATED_TEST_TARGET_MODEL_JSON, TEST_TYPE_REFERENCE);
        assertHookTargetProperties_forUpdateHook(targetModel);
    }

    @Test
    public void testHookTargetModelToTypedTargetModel() {
        final HookTargetModel targetModel = HookTargetModel.of(TEST_TARGET_MODEL_MAP);
        final TestResourceHookTargetModel typedTargetModel = HookTargetModel.of(targetModel, TestResourceHookTargetModel.class);
        assertHookTargetProperties(typedTargetModel);
    }

    @Test
    public void testHookTargetModelWithAdditionalProperties() throws Exception {
        final Map<String, Object> genericTargetMap = ImmutableMap.of("UnknownKey1", "UnknownValue", "UnknownKey2", 42);
        final Map<String, Object> targetModelMap = ImmutableMap.of(RESOURCE_PROPERTIES_KEY, genericTargetMap);

        final ResourceHookTargetModel<
            GenericTestResource> targetModel = HookTargetModel.of(targetModelMap, GenericTestResourceHookTargetModel.class);

        final GenericTestResource resourceProperties = targetModel.getResourceProperties();
        Assertions.assertEquals(genericTargetMap, resourceProperties.getProperties());
        Assertions.assertEquals(genericTargetMap, resourceProperties.getAdditionalProperties());
        Assertions.assertEquals("UnknownValue", resourceProperties.get("UnknownKey1"));
        Assertions.assertEquals(42, resourceProperties.get("UnknownKey2"));
        Assertions.assertEquals(
            "{\"ResourceProperties\":{\"UnknownKey2\":42,\"UnknownKey1\":\"UnknownValue\"},\"PreviousResourceProperties\":null}",
            OBJECT_MAPPER.writeValueAsString(targetModel));
        Assertions.assertEquals("{\"UnknownKey2\":42,\"UnknownKey1\":\"UnknownValue\"}",
            OBJECT_MAPPER.writeValueAsString(resourceProperties));
    }

    @Test
    public void testStackHookTargetModel() throws Exception {
        final String template = "{\"key1\":\"value1\"}";
        final String previousTemplate = "{\"previousKey1\":\"previousValue1\"}";
        final String resolvedTemplate = "{\"resolvedKey1\":\"resolvedValue1\"}";
        final List<ChangedResource> changedResources = ImmutableList
            .of(ChangedResource.builder().logicalResourceId("SomeLogicalResourceId").resourceType("AWS::S3::Bucket")
                .action("CREATE").lineNumber(11).previousResourceProperties("{\"BucketName\": \"some-prev-bucket-name\"")
                .resourceProperties("{\"BucketName\": \"some-bucket-name\"").build());

        final Map<String, Object> targetModelMap = ImmutableMap.of("Template", template, "PreviousTemplate", previousTemplate,
            "ResolvedTemplate", resolvedTemplate, "ChangedResources", changedResources);

        final StackHookTargetModel targetModel = HookTargetModel.of(targetModelMap, StackHookTargetModel.class);

        Assertions.assertEquals(template, targetModel.getTemplate());
        Assertions.assertEquals(previousTemplate, targetModel.getPreviousTemplate());
        Assertions.assertEquals(resolvedTemplate, targetModel.getResolvedTemplate());
        Assertions.assertEquals(changedResources, targetModel.getChangedResources());
        Assertions.assertNull(targetModel.getHookTargetTypeReference());
        Assertions.assertEquals(
            "{\"Template\":\"{\\\"key1\\\":\\\"value1\\\"}\",\"PreviousTemplate\":\"{\\\"previousKey1\\\":\\\""
                + "previousValue1\\\"}\",\"ResolvedTemplate\":\"{\\\"resolvedKey1\\\":\\\"resolvedValue1\\\"}\",\"ChangedResources\""
                + ":[{\"LogicalResourceId\":\"SomeLogicalResourceId\",\"ResourceType\":\"AWS::S3::Bucket\",\"LineNumber\":"
                + "11,\"Action\":\"CREATE\",\"ResourceProperties\":\"{\\\"BucketName\\\": \\\"some-bucket-name\\\"\","
                + "\"PreviousResourceProperties\":\"{\\\"BucketName\\\": \\\"some-prev-bucket-name\\\"\"}]}",
            OBJECT_MAPPER.writeValueAsString(targetModel));
    }

    @Test
    public void testStackHookTargetModelWithAdditionalPropertiesInInput() throws Exception {
        final String template = "{\"key1\":\"value1\"}";
        final String previousTemplate = "{\"previousKey1\":\"previousValue1\"}";
        final String resolvedTemplate = "{\"resolvedKey1\":\"resolvedValue1\"}";
        final String extraneousProperty = "{\"extraKey\":\"extraValue\"}";
        final List<ChangedResource> changedResources = ImmutableList
            .of(ChangedResource.builder().logicalResourceId("SomeLogicalResourceId").resourceType("AWS::S3::Bucket")
                .action("CREATE").lineNumber(11).previousResourceProperties("{\"BucketName\": \"some-prev-bucket-name\"")
                .resourceProperties("{\"BucketName\": \"some-bucket-name\"").build());

        final Map<String, Object> targetModelMap = ImmutableMap.of("Template", template, "PreviousTemplate", previousTemplate,
            "ResolvedTemplate", resolvedTemplate, "ChangedResources", changedResources, "ExtraProperty", extraneousProperty);

        final StackHookTargetModel targetModel = HookTargetModel.of(targetModelMap, StackHookTargetModel.class);

        Assertions.assertEquals(template, targetModel.getTemplate());
        Assertions.assertEquals(previousTemplate, targetModel.getPreviousTemplate());
        Assertions.assertEquals(resolvedTemplate, targetModel.getResolvedTemplate());
        Assertions.assertEquals(changedResources, targetModel.getChangedResources());
        Assertions.assertNull(targetModel.getHookTargetTypeReference());

        Assertions.assertEquals("{\"Template\":\"{\\\"key1\\\":\\\"value1\\\"}\",\"PreviousTemplate\":\"{\\\"previousKey1\\\":"
            + "\\\"previousValue1\\\"}\",\"ResolvedTemplate\":\"{\\\"resolvedKey1\\\":\\\"resolvedValue1\\\"}\","
            + "\"ChangedResources\":[{\"LogicalResourceId\":\"SomeLogicalResourceId\",\"ResourceType\":"
            + "\"AWS::S3::Bucket\",\"LineNumber\":11,\"Action\":\"CREATE\",\"ResourceProperties\":\"{"
            + "\\\"BucketName\\\": \\\"some-bucket-name\\\"\",\"PreviousResourceProperties\":\"{\\\"BucketName\\\":"
            + " \\\"some-prev-bucket-name\\\"\"}]}", OBJECT_MAPPER.writeValueAsString(targetModel));
    }

    @Test
    public void testStackHookTargetModelWithMissingPropertiesInInput() throws Exception {
        final String template = "{\"key1\":\"value1\"}";
        final String resolvedTemplate = "{\"resolvedKey1\":\"resolvedValue1\"}";
        final List<ChangedResource> changedResources = ImmutableList
            .of(ChangedResource.builder().logicalResourceId("SomeLogicalResourceId").resourceType("AWS::S3::Bucket")
                .action("CREATE").previousResourceProperties("{\"BucketName\": \"some-prev-bucket-name\"")
                .resourceProperties("{\"BucketName\": \"some-bucket-name\"").build());

        final Map<String, Object> targetModelMap = ImmutableMap.of("Template", template, "ResolvedTemplate", resolvedTemplate,
            "ChangedResources", changedResources);

        final StackHookTargetModel targetModel = HookTargetModel.of(targetModelMap, StackHookTargetModel.class);

        Assertions.assertEquals(template, targetModel.getTemplate());
        Assertions.assertNull(targetModel.getPreviousTemplate());
        Assertions.assertEquals(resolvedTemplate, targetModel.getResolvedTemplate());
        Assertions.assertEquals(changedResources, targetModel.getChangedResources());
        Assertions.assertNull(targetModel.getHookTargetTypeReference());
        Assertions.assertEquals(
            "{\"Template\":\"{\\\"key1\\\":\\\"value1\\\"}\",\"PreviousTemplate\":null,\"ResolvedTemplate\":"
                + "\"{\\\"resolvedKey1\\\":\\\"resolvedValue1\\\"}\",\"ChangedResources\":[{\"LogicalResourceId\":"
                + "\"SomeLogicalResourceId\",\"ResourceType\":\"AWS::S3::Bucket\",\"LineNumber\":null,\"Action\":"
                + "\"CREATE\",\"ResourceProperties\":\"{\\\"BucketName\\\": \\\"some-bucket-name\\\"\","
                + "\"PreviousResourceProperties\":\"{\\\"BucketName\\\": \\\"some-prev-bucket-name\\\"\"}]}",
            OBJECT_MAPPER.writeValueAsString(targetModel));
    }

    @Test
    public void testHookTargetTypeWithNullValue() {
        final HookTargetModel targetModel = HookTargetModel.of(null);
        Assertions.assertNull(targetModel);
        TestResourceHookTargetModel typedTargetModel = HookTargetModel.of(null, TestResourceHookTargetModel.class);
        Assertions.assertNull(typedTargetModel);
        typedTargetModel = HookTargetModel.of(null, TEST_TYPE_REFERENCE);
        Assertions.assertNull(typedTargetModel);
    }

    @Test
    public void testNonConcreteHookTargetModelType_shouldThrowException() {
        Stream.of(HookTargetModel.class, ResourceHookTargetModel.class, new HookTargetModel() {
            protected HookTargetType getHookTargetType() {
                return null;
            }

            protected TypeReference<? extends HookTarget> getHookTargetTypeReference() {
                return null;
            }

            protected TypeReference<? extends HookTargetModel> getTargetModelTypeReference() {
                return null;
            }
        }.getClass()).forEach(clazz -> {
            org.assertj.core.api.Assertions.assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> HookTargetModel.of(TEST_TARGET_MODEL_MAP, clazz))
                .withMessageContaining("Target Model can only be converted to concrete types.");
        });
    }

    @Test
    public void testCreateHookTarget_fromMapWithNullKey() {
        final Map<String, Object> map = new HashMap<>();
        map.put(RESOURCE_PROPERTIES_KEY, TEST_RESOURCE_PROPERTIES_MAP);
        map.put(null, UPDATED_TEST_RESOURCE_PROPERTIES_MAP);

        org.assertj.core.api.Assertions.assertThatExceptionOfType(NullPointerException.class)
            .isThrownBy(() -> HookTargetModel.of(map, TestResourceHookTargetModel.class)).withMessageContaining("Null key");
    }

    @Test
    public void testGetTargetModelAsJsonObject() {
        final JSONObject expected = new JSONObject(TEST_TARGET_MODEL_MAP);
        final TestResourceHookTargetModel targetModel = HookTargetModel.of(TEST_TARGET_MODEL_MAP,
            TestResourceHookTargetModel.class);
        Assertions.assertEquals(expected.toMap(), targetModel.getTargetModelAsJSONObject().toMap());
    }

    @Test
    public void testGetTargetModelAsMap() {
        final TestResourceHookTargetModel targetModel = HookTargetModel.of(TEST_TARGET_MODEL_MAP,
            TestResourceHookTargetModel.class);
        Assertions.assertEquals(TEST_TARGET_MODEL_MAP, targetModel.getTargetModelAsMap());
    }

    @Test
    public void testExtractTargetModelFromNull() {
        Assertions.assertEquals(Collections.emptyMap(), HookTargetModel.extractRawTargetModel(null));
    }

    @Test
    public void testDeserializeHookTargetModel() throws Exception {
        final String json = "{\"TargetProperties\":{\"TargetProperty1\":\"TargetPropertyVal\"}}";
        final Map<String, Object> genericTarget = ImmutableMap.of("TargetProperty1", "TargetPropertyVal");
        final Map<String, Object> genericTargetModel = ImmutableMap.of("TargetProperties", genericTarget);
        final HookTargetModel expected = HookTargetModel.of(genericTargetModel);

        final HookTargetModel targetModel = OBJECT_MAPPER.readValue(json, HookTargetModel.class);
        Assertions.assertEquals(expected, targetModel);
    }

    @Test
    public void testSerializeHookTargetModel() throws Exception {
        final String expected = "{\"TargetProperties\":{\"TargetProperty1\":\"TargetPropertyVal\"}}";

        final Map<String, Object> genericTarget = ImmutableMap.of("TargetProperty1", "TargetPropertyVal");
        final Map<String, Object> genericTargetModel = ImmutableMap.of("TargetProperties", genericTarget);

        final HookTargetModel targetModel = HookTargetModel.of(genericTargetModel);
        final String json = OBJECT_MAPPER.writeValueAsString(targetModel);
        Assertions.assertEquals(expected, json);
    }

    @Test
    public void testDeserializeGeneralHookTargetModel() throws Exception {
        final String json = "{\"TargetProperties\":{\"TargetProperty1\":\"TargetPropertyVal\"}}";
        final Map<String, Object> genericTarget = ImmutableMap.of("TargetProperty1", "TargetPropertyVal");
        final Map<String, Object> genericTargetModel = ImmutableMap.of("TargetProperties", genericTarget);
        final HookTargetModel expected = HookTargetModel.of(genericTargetModel);

        final GenericHookTargetModel targetModel = OBJECT_MAPPER.readValue(json, GenericHookTargetModel.class);
        Assertions.assertEquals(expected, targetModel);
    }

    @Test
    public void testSerializeGeneralHookTargetModel() throws Exception {
        final String expected = "{\"TargetProperties\":{\"TargetProperty1\":\"TargetPropertyVal\"}}";

        final Map<String, Object> genericTarget = ImmutableMap.of("TargetProperty1", "TargetPropertyVal");
        final Map<String, Object> genericTargetModel = ImmutableMap.of("TargetProperties", genericTarget);

        final GenericHookTargetModel targetModel = HookTargetModel.of(genericTargetModel, GenericHookTargetModel.class);
        final String json = OBJECT_MAPPER.writeValueAsString(targetModel);
        Assertions.assertEquals(expected, json);
    }

    @Test
    public void testDeserializeResourceHookTargetModel() throws Exception {
        final TestResourceHookTargetModel expected = HookTargetModel.of(TEST_TARGET_MODEL_MAP, TestResourceHookTargetModel.class);
        final TestResourceHookTargetModel targetModel = OBJECT_MAPPER.readValue(TEST_TARGET_MODEL_JSON,
            TestResourceHookTargetModel.class);
        Assertions.assertEquals(expected, targetModel);
    }

    @Test
    public void testSerializeResourceHookTargetModel() throws Exception {
        final TestResourceHookTargetModel targetModel = HookTargetModel.of(TEST_TARGET_MODEL_MAP,
            TestResourceHookTargetModel.class);

        final String json = OBJECT_MAPPER.writeValueAsString(targetModel);
        Assertions.assertEquals(TEST_TARGET_MODEL_JSON, json);
    }

    @Test
    public void testLoadSchema() {
        final String emptySchema = "{}";
        final String testSchema = Objects.requireNonNull(loadFileStream("test-target-schema.json"));
        final JSONObject expected = new JSONObject(new JSONTokener(testSchema));

        // Testing schemas that are invalid, should result in empty schema.
        Stream.of(null, "null", "", " ", "{", "}", "{{\"key\": \"val\"}", "test-target-schema.yaml", "does-not-exist.json")
            .forEach(s -> {
                final JSONObject result = ResourceHookTarget.loadSchema(s, TestResource.class);
                Assertions.assertNotNull(result);
                Assertions.assertEquals(0, result.length());
                Assertions.assertEquals(emptySchema, result.toString());
                Assertions.assertEquals(Collections.emptyMap(), result.toMap());
            });

        // Testing valid schema. Either provided as string or read from file
        Stream.of(testSchema, "test-target-schema.json").forEach(s -> {
            final JSONObject result = ResourceHookTarget.loadSchema(s, TestResource.class);
            Assertions.assertNotNull(result);
            Assertions.assertEquals(expected.toString(), result.toString());
        });
    }

    @SuppressWarnings("SameParameterValue")
    private static String loadFileStream(final String fileName) {
        final File file = new File(String.format(TEST_DATA_BASE_PATH, fileName));
        try {
            return IOUtils.toString(new FileInputStream(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void assertHookTargetProperties(final TestResourceHookTargetModel targetModel) {
        Assertions.assertNotNull(targetModel);
        Assertions.assertEquals(HookTargetType.RESOURCE, targetModel.getHookTargetType());
        Assertions.assertEquals(TEST_TARGET_MODEL, targetModel);
        Assertions.assertEquals(TEST_TARGET_MODEL_MAP, targetModel.getTargetModel());

        final TestResource resourceProperties = targetModel.getResourceProperties();
        Assertions.assertNotNull(resourceProperties);
        Assertions.assertEquals(HookTargetType.RESOURCE, resourceProperties.getHookTargetType());
        Assertions.assertEquals(TEST_RESOURCE, resourceProperties);
        Assertions.assertEquals(TEST_RESOURCE_PROPERTIES_MAP, targetModel.get(RESOURCE_PROPERTIES_KEY));
        Assertions.assertEquals(TEST_PROPERTY_1, resourceProperties.getProperty1());
        Assertions.assertEquals(TEST_PROPERTY_2, resourceProperties.getProperty2());
        Assertions.assertEquals(TEST_TAGS, resourceProperties.getTags());

        Assertions.assertEquals(TEST_RESOURCE_PROPERTIES_MAP, resourceProperties.getProperties());
        Assertions.assertTrue(resourceProperties.hasProperty(TEST_PROPERTY_1_KEY));
        Assertions.assertEquals(TEST_PROPERTY_1, resourceProperties.get(TEST_PROPERTY_1_KEY));
        Assertions.assertTrue(resourceProperties.hasProperty(TEST_PROPERTY_2_KEY));
        Assertions.assertEquals(TEST_PROPERTY_2, resourceProperties.get(TEST_PROPERTY_2_KEY));
        Assertions.assertTrue(resourceProperties.hasProperty(TEST_TAGS_KEY));
        Assertions.assertEquals(TEST_TAGS, resourceProperties.get(TEST_TAGS_KEY));
    }

    private void assertHookTargetProperties_forUpdateHook(final TestResourceHookTargetModel targetModel) {
        Assertions.assertNotNull(targetModel);
        Assertions.assertEquals(HookTargetType.RESOURCE, targetModel.getHookTargetType());
        Assertions.assertEquals(UPDATED_TARGET_MODEL, targetModel);

        final TestResource resourceProperties = targetModel.getResourceProperties();
        Assertions.assertNotNull(resourceProperties);
        Assertions.assertEquals(HookTargetType.RESOURCE, resourceProperties.getHookTargetType());
        Assertions.assertEquals(UPDATED_TEST_RESOURCE, resourceProperties);
        Assertions.assertEquals(UPDATED_TEST_RESOURCE_PROPERTIES_MAP, targetModel.get(RESOURCE_PROPERTIES_KEY));
        Assertions.assertEquals(UPDATED_TEST_PROPERTY_1, resourceProperties.getProperty1());
        Assertions.assertEquals(TEST_PROPERTY_2, resourceProperties.getProperty2());
        Assertions.assertEquals(TEST_TAGS, resourceProperties.getTags());

        Assertions.assertEquals(UPDATED_TEST_RESOURCE_PROPERTIES_MAP, resourceProperties.getProperties());
        Assertions.assertTrue(resourceProperties.hasProperty(TEST_PROPERTY_1_KEY));
        Assertions.assertEquals(UPDATED_TEST_PROPERTY_1, resourceProperties.get(TEST_PROPERTY_1_KEY));
        Assertions.assertTrue(resourceProperties.hasProperty(TEST_PROPERTY_2_KEY));
        Assertions.assertEquals(TEST_PROPERTY_2, resourceProperties.get(TEST_PROPERTY_2_KEY));
        Assertions.assertTrue(resourceProperties.hasProperty(TEST_TAGS_KEY));
        Assertions.assertEquals(TEST_TAGS, resourceProperties.get(TEST_TAGS_KEY));

        final TestResource previousResourceProperties = targetModel.getPreviousResourceProperties();
        Assertions.assertNotNull(previousResourceProperties);
        Assertions.assertEquals(HookTargetType.RESOURCE, previousResourceProperties.getHookTargetType());
        Assertions.assertEquals(TEST_RESOURCE, previousResourceProperties);
        Assertions.assertEquals(TEST_RESOURCE_PROPERTIES_MAP, targetModel.get(PREVIOUS_RESOURCE_PROPERTIES_KEY));
        Assertions.assertEquals(TEST_PROPERTY_1, previousResourceProperties.getProperty1());
        Assertions.assertEquals(TEST_PROPERTY_2, previousResourceProperties.getProperty2());
        Assertions.assertEquals(TEST_TAGS, previousResourceProperties.getTags());

        Assertions.assertEquals(TEST_RESOURCE_PROPERTIES_MAP, previousResourceProperties.getProperties());
        Assertions.assertTrue(previousResourceProperties.hasProperty(TEST_PROPERTY_1_KEY));
        Assertions.assertEquals(TEST_PROPERTY_1, previousResourceProperties.get(TEST_PROPERTY_1_KEY));
        Assertions.assertTrue(previousResourceProperties.hasProperty(TEST_PROPERTY_2_KEY));
        Assertions.assertEquals(TEST_PROPERTY_2, previousResourceProperties.get(TEST_PROPERTY_2_KEY));
        Assertions.assertTrue(previousResourceProperties.hasProperty(TEST_TAGS_KEY));
        Assertions.assertEquals(TEST_TAGS, previousResourceProperties.get(TEST_TAGS_KEY));
    }
}
