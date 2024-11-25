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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@EqualsAndHashCode(callSuper = false)
@Getter
@NoArgsConstructor
@ToString
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
@JsonDeserialize(as = StackHookTargetModel.class)
public class StackHookTargetModel extends HookTargetModel {
    private static final TypeReference<StackHookTargetModel> MODEL_REFERENCE = new TypeReference<StackHookTargetModel>() {
    };

    @JsonProperty("Template")
    private Object template;

    @JsonProperty("PreviousTemplate")
    private Object previousTemplate;

    @JsonProperty("ResolvedTemplate")
    private Object resolvedTemplate;

    @JsonProperty("ChangedResources")
    private List<ChangedResource> changedResources;

    @Override
    public TypeReference<? extends HookTarget> getHookTargetTypeReference() {
        return null;
    }

    @Override
    public TypeReference<? extends HookTargetModel> getTargetModelTypeReference() {
        return MODEL_REFERENCE;
    }

    @Override
    public final HookTargetType getHookTargetType() {
        return HookTargetType.STACK;
    }
}
