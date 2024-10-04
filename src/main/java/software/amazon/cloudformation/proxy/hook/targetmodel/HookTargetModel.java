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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import javax.annotation.CheckForNull;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NonNull;
import lombok.Setter;
import org.json.JSONObject;
import software.amazon.cloudformation.resource.Serializer;

@Data
@Setter(AccessLevel.PRIVATE)
@JsonDeserialize(as = GenericHookTargetModel.class)
public abstract class HookTargetModel {
    private static final TypeReference<Map<String, Object>> MAP_REFERENCE = new TypeReference<Map<String, Object>>() {
    };

    @JsonIgnore
    private Map<String, Object> targetModel = new HashMap<>();

    void setTargetModelProperty(final String key, final Object value) {
        this.targetModel.put(key, value);
    }

    private void setTargetModel(final Map<String, Object> targetModel) {
        this.targetModel = ImmutableMap.copyOf(targetModel);
    }

    public final Map<String, Object> getTargetModel() {
        return targetModel;
    }

    @JsonIgnore
    public JSONObject getTargetModelAsJSONObject() {
        return new JSONObject(this.targetModel);
    }

    public Map<String, Object> getTargetModelAsMap() {
        return new Serializer().convert(this, MAP_REFERENCE);
    }

    public final Object get(final String key) {
        return this.targetModel.get(key);
    }

    public final Object getOrDefault(final String key, final Object value) {
        return this.targetModel.getOrDefault(key, value);
    }

    public final boolean containsKey(final String key) {
        return this.targetModel.containsKey(key);
    }

    protected abstract HookTargetType getHookTargetType();

    protected abstract TypeReference<? extends HookTarget> getHookTargetTypeReference();

    protected abstract TypeReference<? extends HookTargetModel> getTargetModelTypeReference();

    public static HookTargetModel of(final Object targetModel) {
        return HookTargetModel.of(targetModel, GenericHookTargetModel.class);
    }

    public static <TargetModelT extends HookTargetModel> TargetModelT of(@CheckForNull final Object targetModel,
                                                                         @NonNull final Class<TargetModelT> clazz) {
        if (targetModel == null) {
            return null;
        }

        final TypeReference<TargetModelT> typeReference = new TypeReference<TargetModelT>() {
            @Override
            public Type getType() {
                return clazz;
            }
        };

        return HookTargetModel.of(targetModel, typeReference);
    }

    @SuppressWarnings("unchecked")
    public static <TargetModelT extends HookTargetModel> TargetModelT of(@CheckForNull final Object targetModel,
                                                                         @NonNull TypeReference<TargetModelT> typeReference) {
        if (targetModel == null) {
            return null;
        }

        try {
            final Type type = typeReference.getType();

            final Class<?> clazz;
            if (type instanceof Class) {
                clazz = (Class<?>) type;
            } else {
                clazz = Class.forName(type.getTypeName());
            }

            if (clazz.isInterface() || clazz.isAnonymousClass() || Modifier.isAbstract(clazz.getModifiers())) {
                throw new IllegalArgumentException("Target Model can only be converted to concrete types.");
            }
        } catch (final ClassNotFoundException e) {
            // Do what type checking we can here. Jackson will catch anything else.
        }

        final Map<String, Object> rawTargetModel = extractRawTargetModel(targetModel);
        final HookTargetModel hookTargetModel = new Serializer().convert(rawTargetModel, typeReference);
        hookTargetModel.setTargetModel(rawTargetModel);

        return (TargetModelT) hookTargetModel;
    }

    @VisibleForTesting
    static Map<String, Object> extractRawTargetModel(final Object targetModel) {
        try {
            final Map<?, ?> rawTargetModel;
            if (targetModel == null) {
                rawTargetModel = new HashMap<>();
            } else if (targetModel instanceof HookTargetModel) {
                rawTargetModel = ((HookTargetModel) targetModel).getTargetModel();
            } else if (targetModel instanceof Map<?, ?>) {
                rawTargetModel = (Map<?, ?>) targetModel;
            } else if (targetModel instanceof JSONObject) {
                rawTargetModel = ((JSONObject) targetModel).toMap();
            } else if (targetModel instanceof String) {
                rawTargetModel = new Serializer().deserialize((String) targetModel, MAP_REFERENCE);
            } else {
                rawTargetModel = new Serializer().convert(targetModel, MAP_REFERENCE);
            }

            final Map<String, Object> targetModelMap = new HashMap<>();
            for (final Entry<?, ?> entry : rawTargetModel.entrySet()) {
                final Object key = entry.getKey();
                if (key == null) {
                    throw new NullPointerException("Null key.");
                }
                final Object value = entry.getValue();
                if (value != null) {
                    targetModelMap.put(String.valueOf(key), value);
                }
            }

            return targetModelMap;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
