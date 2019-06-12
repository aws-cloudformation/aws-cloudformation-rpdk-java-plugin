package com.amazonaws.cloudformation.proxy.handler;

import com.amazonaws.cloudformation.proxy.AmazonWebServicesClientProxy;
import com.amazonaws.cloudformation.proxy.Logger;
import com.amazonaws.cloudformation.proxy.ProgressEvent;
import com.amazonaws.cloudformation.proxy.ProxyClient;
import com.amazonaws.cloudformation.proxy.ResourceHandlerRequest;
import com.amazonaws.cloudformation.proxy.StdCallbackContext;
import com.amazonaws.cloudformation.proxy.service.DescribeRequest;
import com.amazonaws.cloudformation.proxy.service.ServiceClient;

public class ReadHandler {

    private final ServiceClient client;
    ReadHandler(ServiceClient client) {
        this.client = client;
    }

    public ProgressEvent<Model, StdCallbackContext> handleRequest(
        final AmazonWebServicesClientProxy proxy,
        final ResourceHandlerRequest<Model> request,
        final StdCallbackContext context,
        final Logger logger) {

        final Model model = request.getDesiredResourceState();
        final StdCallbackContext cxt = context == null ? new StdCallbackContext() : context;
        ProxyClient<ServiceClient> client = proxy.newProxy(() -> this.client);
        return proxy.initiate("client:readRepository", client, model, cxt)
            .request(m -> {
                DescribeRequest.Builder builder = new DescribeRequest.Builder();
                builder.repoName(m.getRepoName());
                builder.throwAccessDenied(m.getAccessDenied() != null && m.getAccessDenied());
                builder.throwThrottleException(m.getThrottle() != null && m.getThrottle());
                return builder.build();
            })
            .call((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::describeRepository))
            .done(r -> {
                model.setRepoName(r.getRepoName());
                model.setArn(r.getRepoArn());
                model.setCreated(r.getCreatedWhen());
                return ProgressEvent.success(model, context);
            });
    }

}
