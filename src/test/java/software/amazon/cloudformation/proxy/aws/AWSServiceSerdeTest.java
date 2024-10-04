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
package software.amazon.cloudformation.proxy.aws;

import static org.assertj.core.api.AssertionsForClassTypes.*;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.core.protocol.MarshallLocation;
import software.amazon.awssdk.core.protocol.MarshallingType;
import software.amazon.awssdk.core.traits.LocationTrait;
import software.amazon.awssdk.services.cloudwatchevents.model.BatchArrayProperties;
import software.amazon.awssdk.services.cloudwatchevents.model.BatchParameters;
import software.amazon.awssdk.services.cloudwatchevents.model.InputTransformer;
import software.amazon.awssdk.services.cloudwatchevents.model.KinesisParameters;
import software.amazon.awssdk.services.cloudwatchevents.model.PutRuleRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.PutTargetsRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.PutTargetsResponse;
import software.amazon.awssdk.services.cloudwatchevents.model.PutTargetsResultEntry;
import software.amazon.awssdk.services.cloudwatchevents.model.Tag;
import software.amazon.awssdk.services.cloudwatchevents.model.Target;
import software.amazon.awssdk.utils.builder.SdkBuilder;
import software.amazon.cloudformation.resource.Serializer;

public class AWSServiceSerdeTest {

    private final Serializer serializer = new Serializer();

    @Test
    public void serdeAwsRequest() throws Exception {
        PutTargetsRequest request = PutTargetsRequest.builder().rule("myrule")
            .targets(Target.builder().id(UUID.randomUUID().toString())
                .kinesisParameters(KinesisParameters.builder().partitionKeyPath("blah/blah").build()).build())
            .build();

        String json = serializer.serialize(request);
        PutTargetsRequest deser = serializer.deserialize(json, new TypeReference<PutTargetsRequest>() {
        });
        assertThat(deser).isEqualTo(request);
    }

    @Test
    public void serdeAwsResponse() throws Exception {
        PutTargetsResponse response = PutTargetsResponse.builder().failedEntryCount(1)
            .failedEntries(PutTargetsResultEntry.builder().targetId(UUID.randomUUID().toString()).errorCode("blue/blue").build())
            .build();

        String serialized = serializer.serialize(response);
        PutTargetsResponse deser = serializer.deserialize(serialized, new TypeReference<PutTargetsResponse>() {
        });
        assertThat(deser).isEqualTo(response);
    }

    @Test
    public void serdeAwsRequestWithMultipleInList() throws Exception {
        PutRuleRequest request = PutRuleRequest.builder().description("desc").eventPattern("*/**")
            .tags(Tag.builder().key("one").value("1").build(), Tag.builder().key("two").value("2").build()).build();
        String serialized = serializer.serialize(request);
        PutRuleRequest deser = serializer.deserialize(serialized, new TypeReference<PutRuleRequest>() {
        });
        assertThat(deser).isEqualTo(request);
    }

    @Test
    public void deserAwsRequestNonStrict() throws Exception {
        String putRuleRequest = "{\"Description\":\"desc\",\"EventsNonProperty\":false,\"EventPattern\":\"*/**\"}";
        PutRuleRequest request = serializer.deserialize(putRuleRequest, new TypeReference<PutRuleRequest>() {
        });
        assertThat(request).isNotNull();
        assertThat(request.description()).isEqualTo("desc");
        assertThat(request.eventPattern()).isEqualTo("*/**");
    }

    @Test
    public void deserAwsRequestStrict() throws Exception {
        final String putRuleRequest = "{\"Description\":\"desc\",\"EventsNonProperty\":false,\"EventPattern\":\"*/**\"}";
        Assertions.assertThrows(JsonMappingException.class,
            () -> serializer.deserializeStrict(putRuleRequest, new TypeReference<PutRuleRequest>() {
            }));
    }

    @Test
    public void deserAwsRequestStrict2() throws Exception {
        final String putRuleRequest = "{\"Description\":\"desc\",\"EventsNonProperty\":[1, 2, 3],\"EventPattern\":\"*/**\"}";
        Assertions.assertThrows(JsonMappingException.class,
            () -> serializer.deserializeStrict(putRuleRequest, new TypeReference<PutRuleRequest>() {
            }));
    }

    @Test
    public void deserAwsRequestNonStrict2() throws Exception {
        final String putRuleRequest = "{\"Description\":\"desc\",\"EventsNonProperty\":[1, 2, 3],\"EventPattern\":\"*/**\"}";
        PutRuleRequest request = serializer.deserialize(putRuleRequest, new TypeReference<PutRuleRequest>() {
        });
        assertThat(request).isNotNull();
        assertThat(request.description()).isEqualTo("desc");
        assertThat(request.eventPattern()).isEqualTo("*/**");
    }

    @Test
    public void deserAwsRequestNonStrict3() throws Exception {
        final String putRuleRequest = "{\"Description\":\"desc\",\"EventsNonProperty\":{\"1\": 2},\"EventPattern\":\"*/**\"}";
        PutRuleRequest request = serializer.deserialize(putRuleRequest, new TypeReference<PutRuleRequest>() {
        });
        assertThat(request).isNotNull();
        assertThat(request.description()).isEqualTo("desc");
        assertThat(request.eventPattern()).isEqualTo("*/**");
    }

    @Test
    public void deserAwsRequestNonStrict4() throws Exception {
        final String putRuleRequest = "{\"Description\":\"desc\",\"EventPattern\":\"*/**\",\"EventsNonProperty\":[1, 2, 3]}";
        PutRuleRequest request = serializer.deserialize(putRuleRequest, new TypeReference<PutRuleRequest>() {
        });
        assertThat(request).isNotNull();
        assertThat(request.description()).isEqualTo("desc");
        assertThat(request.eventPattern()).isEqualTo("*/**");
    }

    @Test
    public void deserAwsRequestNonStrict5() throws Exception {
        final String putRuleRequest = "{\"Description\":\"desc\",\"EventPattern\":\"*/**\",\"EventsNonProperty\":{\"1\": 2}}";
        PutRuleRequest request = serializer.deserialize(putRuleRequest, new TypeReference<PutRuleRequest>() {
        });
        assertThat(request).isNotNull();
        assertThat(request.description()).isEqualTo("desc");
        assertThat(request.eventPattern()).isEqualTo("*/**");
    }

    public static class NoBuilderMethod implements SdkPojo {
        @Override
        public List<SdkField<?>> sdkFields() {
            return Collections.emptyList();
        }
    }

    @Test
    public void noBuilderMethod() {
        JsonMappingException exception = Assertions.assertThrows(JsonMappingException.class,
            () -> serializer.deserialize("{}", new TypeReference<NoBuilderMethod>() {
            }));
        assertThat(exception.getMessage()).contains("Could not create builder for SdkPojo");
    }

    @Test
    public void mapFieldSerde() throws Exception {
        final Map<String, String> paths = new HashMap<>();
        paths.put("$.name", "0");
        paths.put("$.type", "this");
        PutTargetsRequest request = PutTargetsRequest.builder().rule("myrule")
            .targets(Target.builder()
                .batchParameters(BatchParameters.builder().jobName("MY-RULE-JOB")
                    .arrayProperties(BatchArrayProperties.builder().size(10).build()).build())
                .inputTransformer(InputTransformer.builder().inputPathsMap(paths).build()).build())
            .build();

        String json = serializer.serialize(request);
        PutTargetsRequest deserialized = serializer.deserialize(json, new TypeReference<PutTargetsRequest>() {
        });
        assertThat(deserialized).isEqualTo(request);
    }

    @lombok.EqualsAndHashCode
    @lombok.ToString
    @lombok.Getter
    public static class AllPrimitives implements SdkPojo {

        private static final SdkField<String> STRING_VALUE_FieLD = SdkField.builder(MarshallingType.STRING)
            .getter(getter(AllPrimitives::getStringValue)).setter(setter(Builder::stringValue))
            .traits(LocationTrait.builder().location(MarshallLocation.PAYLOAD).locationName("StringValue").build()).build();

        private static final SdkField<Float> FLOAT_VALUE_FieLD = SdkField.builder(MarshallingType.FLOAT)
            .getter(getter(AllPrimitives::getFloatValue)).setter(setter(Builder::floatValue))
            .traits(LocationTrait.builder().location(MarshallLocation.PAYLOAD).locationName("FloatValue").build()).build();

        private static final SdkField<Double> DOUBLE_VALUE_FieLD = SdkField.builder(MarshallingType.DOUBLE)
            .getter(getter(AllPrimitives::getDoubleValue)).setter(setter(Builder::doubleValue))
            .traits(LocationTrait.builder().location(MarshallLocation.PAYLOAD).locationName("DoubleValue").build()).build();

        private static final SdkField<Integer> INT_VALUE_FieLD = SdkField.builder(MarshallingType.INTEGER)
            .getter(getter(AllPrimitives::getIntValue)).setter(setter(Builder::intValue))
            .traits(LocationTrait.builder().location(MarshallLocation.PAYLOAD).locationName("IntValue").build()).build();

        private static final SdkField<Long> LONG_VALUE_FieLD = SdkField.builder(MarshallingType.LONG)
            .getter(getter(AllPrimitives::getLongValue)).setter(setter(Builder::longValue))
            .traits(LocationTrait.builder().location(MarshallLocation.PAYLOAD).locationName("LongValue").build()).build();

        private static final SdkField<Boolean> BOOLEAN_VALUE_FieLD = SdkField.builder(MarshallingType.BOOLEAN)
            .getter(getter(AllPrimitives::getBooleanValue)).setter(setter(Builder::booleanValue))
            .traits(LocationTrait.builder().location(MarshallLocation.PAYLOAD).locationName("BooleanValue").build()).build();

        private static final SdkField<BigDecimal> BIG_DECIMAL_VALUE_FieLD = SdkField.builder(MarshallingType.BIG_DECIMAL)
            .getter(getter(AllPrimitives::getBigDecimalValue)).setter(setter(Builder::bigDecimalValue))
            .traits(LocationTrait.builder().location(MarshallLocation.PAYLOAD).locationName("BigDecimalValue").build()).build();

        private static final SdkField<Instant> INSTANT_VALUE_FieLD = SdkField.builder(MarshallingType.INSTANT)
            .getter(getter(AllPrimitives::getInstantValue)).setter(setter(Builder::instantValue))
            .traits(LocationTrait.builder().location(MarshallLocation.PAYLOAD).locationName("InstantValue").build()).build();

        private static final SdkField<SdkBytes> SDK_BYTES_VALUE_FieLD = SdkField.builder(MarshallingType.SDK_BYTES)
            .getter(getter(AllPrimitives::getByteValue)).setter(setter(Builder::byteValue))
            .traits(LocationTrait.builder().location(MarshallLocation.PAYLOAD).locationName("ByteValue").build()).build();

        private static final List<SdkField<?>> SDK_FIELDS = Collections
            .unmodifiableList(Arrays.asList(STRING_VALUE_FieLD, FLOAT_VALUE_FieLD, BOOLEAN_VALUE_FieLD, LONG_VALUE_FieLD,
                DOUBLE_VALUE_FieLD, INT_VALUE_FieLD, BIG_DECIMAL_VALUE_FieLD, INSTANT_VALUE_FieLD, SDK_BYTES_VALUE_FieLD));

        private final String stringValue;
        private final Float floatValue;
        private final Double doubleValue;
        private final Integer intValue;
        private final SdkBytes byteValue;
        private final Instant instantValue;
        private final BigDecimal bigDecimalValue;
        private final Boolean booleanValue;
        private final Long longValue;

        private AllPrimitives(Builder b) {
            this.stringValue = b.stringValue;
            this.floatValue = b.floatValue;
            this.doubleValue = b.doubleValue;
            this.byteValue = b.byteValue;
            this.intValue = b.intValue;
            this.instantValue = b.instantValue;
            this.bigDecimalValue = b.bigDecimalValue;
            this.booleanValue = b.booleanValue;
            this.longValue = b.longValue;
        }

        private static <T> Function<Object, T> getter(Function<AllPrimitives, T> g) {
            return obj -> g.apply((AllPrimitives) obj);
        }

        private static <T> BiConsumer<Object, T> setter(BiConsumer<Builder, T> s) {
            return (obj, val) -> s.accept((Builder) obj, val);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder implements SdkBuilder<Builder, AllPrimitives>, SdkPojo {

            private String stringValue;
            private Float floatValue;
            private Double doubleValue;
            private SdkBytes byteValue;
            private Integer intValue;
            private Instant instantValue;
            private BigDecimal bigDecimalValue;
            private Boolean booleanValue;
            private Long longValue;

            public Builder stringValue(String stringValue) {
                this.stringValue = stringValue;
                return this;
            }

            public Builder floatValue(Float floatValue) {
                this.floatValue = floatValue;
                return this;
            }

            public Builder doubleValue(Double doubleValue) {
                this.doubleValue = doubleValue;
                return this;
            }

            public Builder byteValue(SdkBytes byteValue) {
                this.byteValue = byteValue;
                return this;
            }

            public Builder intValue(Integer intValue) {
                this.intValue = intValue;
                return this;
            }

            public Builder instantValue(Instant instantValue) {
                this.instantValue = instantValue;
                return this;
            }

            public Builder bigDecimalValue(BigDecimal bigDecimalValue) {
                this.bigDecimalValue = bigDecimalValue;
                return this;
            }

            public Builder booleanValue(Boolean booleanValue) {
                this.booleanValue = booleanValue;
                return this;
            }

            public Builder longValue(Long longValue) {
                this.longValue = longValue;
                return this;
            }

            Builder() {
            }

            @Override
            public AllPrimitives build() {
                return new AllPrimitives(this);
            }

            @Override
            public List<SdkField<?>> sdkFields() {
                return SDK_FIELDS;
            }
        }

        @Override
        public List<SdkField<?>> sdkFields() {
            return SDK_FIELDS;
        }
    }

    @Test
    public void allPrimitives() throws Exception {
        AllPrimitives primitives = AllPrimitives.builder().stringValue("hi there").intValue(10).floatValue(10.2f)
            .booleanValue(false).longValue(10L).doubleValue(100.5)
            .byteValue(SdkBytes.fromByteArray("to here".getBytes(StandardCharsets.UTF_8))).build();

        String json = serializer.serialize(primitives);
        AllPrimitives deserialized = serializer.deserialize(json, new TypeReference<AllPrimitives>() {
        });
        assertThat(deserialized).isEqualTo(primitives);
    }

    @Test
    public void allPrimitives2() throws Exception {
        AllPrimitives primitives = AllPrimitives.builder().stringValue("hi there").intValue(10).floatValue(10.0f) // serializes to
                                                                                                                  // 10
            .booleanValue(false).longValue(10L).doubleValue(100.0d) // serializes to 100
            .byteValue(SdkBytes.fromByteArray("to here".getBytes(StandardCharsets.UTF_8))).build();

        String json = serializer.serialize(primitives);
        AllPrimitives deserialized = serializer.deserialize(json, new TypeReference<AllPrimitives>() {
        });
        assertThat(deserialized).isEqualTo(primitives);
    }

    @Test
    public void bigDecimal() throws Exception {
        AllPrimitives primitives = AllPrimitives.builder().stringValue("hi there").intValue(10).floatValue(10.0f) // serializes to
                                                                                                                  // 10
            .booleanValue(false).longValue(10L).doubleValue(100.0d) // serializes to 100
            .byteValue(SdkBytes.fromByteArray("to here".getBytes(StandardCharsets.UTF_8)))
            .bigDecimalValue(BigDecimal.valueOf(100.505d)).build();
        String json = serializer.serialize(primitives);
        AllPrimitives deserialized = serializer.deserialize(json, new TypeReference<AllPrimitives>() {
        });
        assertThat(deserialized).isEqualTo(primitives);
    }

    @Test
    public void instantValue() throws Exception {
        AllPrimitives primitives = AllPrimitives.builder().stringValue("hi there").intValue(10).floatValue(10.0f) // serializes to
                                                                                                                  // 10
            .booleanValue(false).longValue(10L).doubleValue(100.0d) // serializes to 100
            .byteValue(SdkBytes.fromByteArray("to here".getBytes(StandardCharsets.UTF_8))).instantValue(Instant.now()).build();
        String json = serializer.serialize(primitives);
        AllPrimitives deserialized = serializer.deserialize(json, new TypeReference<AllPrimitives>() {
        });
        assertThat(deserialized).isEqualTo(primitives);

    }

    //
    // Breaking deser tests
    //
    @Test
    public void notObjectStart() {
        JsonMappingException exception = Assertions.assertThrows(JsonMappingException.class,
            () -> serializer.deserialize("[]", new TypeReference<AllPrimitives>() {
            }));
        assertThat(exception.getMessage()).contains("Expected to be in START_OBJECT got");
    }

    @Test
    public void noFieldPresent() {
        Assertions.assertThrows(JsonParseException.class,
            () -> serializer.deserialize("{\"StringValue\": \"ok\",1: \"break\"}", new TypeReference<AllPrimitives>() {
            }));
    }

    @Test
    public void typeMismatchError() {
        final TypeReference<AllPrimitives> reference = new TypeReference<AllPrimitives>() {
        };
        JsonMappingException exception = Assertions.assertThrows(JsonMappingException.class,
            () -> serializer.deserialize("{\"BooleanValue\": 1}", reference));
        assertThat(exception.getMessage())
            .contains("Type mismatch, expecting " + MarshallingType.BOOLEAN + " got int/float/double/big_decimal/instant");

        exception = Assertions.assertThrows(JsonMappingException.class,
            () -> serializer.deserialize("{\"BooleanValue\": \"T\"}", reference));
        assertThat(exception.getMessage()).contains("Type mismatch, expecting " + MarshallingType.BOOLEAN + " got string/bytes");

        final TypeReference<InputTransformer> typeReference = new TypeReference<InputTransformer>() {
        };
        exception = Assertions.assertThrows(JsonMappingException.class,
            () -> serializer.deserialize("{\"InputPathsMap\": [1,2]}", typeReference));
        assertThat(exception.getMessage()).contains("Type mismatch, expecting " + MarshallingType.MAP + " got List type");
    }

    @lombok.EqualsAndHashCode
    @lombok.ToString
    @lombok.Getter
    private static class PojoContainingDocument implements SdkPojo {

        private final Document document;

        private static final SdkField<Document> DOCUMENT_VALUE_FIELD = SdkField.builder(MarshallingType.DOCUMENT)
            .getter(documentGetter(PojoContainingDocument::getDocument))
            .setter(documentSetter(PojoContainingDocument.Builder::documentValue))
            .traits(LocationTrait.builder().location(MarshallLocation.PAYLOAD).locationName("DocumentValue").build()).build();
        private static final List<SdkField<?>> SDK_FIELDS = Collections.singletonList(DOCUMENT_VALUE_FIELD);

        @Override
        public List<SdkField<?>> sdkFields() {
            return SDK_FIELDS;
        }

        public static PojoContainingDocument.Builder builder() {
            return new PojoContainingDocument.Builder();
        }

        private PojoContainingDocument(Builder builder) {
            this.document = builder.documentValue;
        }

        public static class Builder implements SdkBuilder<PojoContainingDocument.Builder, PojoContainingDocument>, SdkPojo {

            private Document documentValue;

            public PojoContainingDocument.Builder documentValue(Document documentValue) {
                this.documentValue = documentValue;
                return this;
            }

            Builder() {
            }

            @Override
            public PojoContainingDocument build() {
                return new PojoContainingDocument(this);
            }

            @Override
            public List<SdkField<?>> sdkFields() {
                return SDK_FIELDS;
            }
        }

        // static wrappers of getDocument & putDocument
        private static Function<Object, Document> documentGetter(Function<PojoContainingDocument, Document> g) {
            return obj -> g.apply((PojoContainingDocument) obj);
        }

        private static BiConsumer<Object, Document> documentSetter(BiConsumer<PojoContainingDocument.Builder, Document> s) {
            return (obj, val) -> s.accept((PojoContainingDocument.Builder) obj, val);
        }
    }

    @Test
    public void testDocumentFieldSerde() throws Exception {
        Document template = Document.mapBuilder().putString("type", "Foo")
            .putDocument("fooConfigurationDocument1",
                Document.mapBuilder()
                    .putDocument("fooConfigurationDocumentNestedData", Document.mapBuilder().putString("name", "value").build())
                    .build())
            .putDocument("fooConfigurationDocument2", Document.mapBuilder().putNumber("numberField1", 0.1).build())
            .putBoolean("booleanField1", false).putNumber("numberField2", 10).build();
        PojoContainingDocument pojoContainingDocument = PojoContainingDocument.builder().documentValue(template).build();
        String json = serializer.serialize(pojoContainingDocument);
        PojoContainingDocument deserialized = serializer.deserialize(json, new TypeReference<PojoContainingDocument>() {
        });
        assertThat(deserialized).isEqualTo(pojoContainingDocument);
    }
}
