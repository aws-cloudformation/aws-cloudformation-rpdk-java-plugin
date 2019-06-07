package com.amazonaws.cloudformation.proxy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * This interface describes the response object for the provisioning request
 * @param <ResourceT> Type of resource model being provisioned
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class HandlerResponse<ResourceT> {
    private String bearerToken;
    private String errorCode;
    private String message;
    private String nextToken;
    private OperationStatus operationStatus;
    private ResourceT resourceModel;
    private StabilizationData stabilizationData;
}
