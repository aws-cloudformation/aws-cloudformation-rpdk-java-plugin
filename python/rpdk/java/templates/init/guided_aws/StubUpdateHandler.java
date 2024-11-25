package {{ package_name }};

// TODO: replace all usage of SdkClient with your service client type, e.g; YourServiceAsyncClient
// import software.amazon.awssdk.services.yourservice.YourServiceAsyncClient;

import software.amazon.awssdk.awscore.AwsResponse;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.cloudformation.exceptions.CfnGeneralServiceException;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ProxyClient;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import software.amazon.cloudformation.proxy.HandlerErrorCode;

public class UpdateHandler extends BaseHandlerStd {
    private Logger logger;
    private static final String ACCESS_DENIED_EXCEPTION_MESSAGE = "not authorized";

    protected ProgressEvent<ResourceModel, CallbackContext> handleRequest(
        final AmazonWebServicesClientProxy proxy,
        final ResourceHandlerRequest<ResourceModel> request,
        final CallbackContext callbackContext,
        final ProxyClient<SdkClient> proxyClient,
        final Logger logger) {

        this.logger = logger;

        final ResourceModel previousModel = request.getPreviousResourceState();

        // TODO: Adjust Progress Chain according to your implementation
        // https://github.com/aws-cloudformation/cloudformation-cli-java-plugin/blob/master/src/main/java/software/amazon/cloudformation/proxy/CallChain.java

        return ProgressEvent.progress(request.getDesiredResourceState(), callbackContext)

            // STEP 1 [check if resource already exists]
            // for more information -> https://docs.aws.amazon.com/cloudformation-cli/latest/userguide/resource-type-test-contract.html
            // if target API does not support 'ResourceNotFoundException' then following check is required
            .then(progress ->
                // STEP 1.0 [initialize a proxy context]
                // If your service API does not return ResourceNotFoundException on update requests against some identifier (e.g; resource Name)
                // and instead returns a 200 even though a resource does not exist, you must first check if the resource exists here
                // NOTE: If your service API throws 'ResourceNotFoundException' for update requests this method is not necessary
                proxy.initiate("{{ call_graph }}::{{ operation }}::PreUpdateCheck", proxyClient, progress.getResourceModel(), progress.getCallbackContext())

                    // STEP 1.1 [initialize a proxy context]
                    .translateToServiceRequest(Translator::translateToReadRequest)

                    // STEP 1.2 [TODO: make an api call]
                    .makeServiceCall((awsRequest, client) -> {
                        AwsResponse awsResponse = null;

                        // TODO: add custom read resource logic
                        // If describe request does not return ResourceNotFoundException, you must throw ResourceNotFoundException based on
                        // awsResponse values

                        logger.log(String.format("%s has successfully been read.", ResourceModel.TYPE_NAME));
                        return awsResponse;
                    })
                    .progress()
            )

            // STEP 2 [first update/stabilize progress chain - required for resource update]
            .then(progress ->
                // STEP 2.0 [initialize a proxy context]
                // Implement client invocation of the update request through the proxyClient, which is already initialised with
                // caller credentials, correct region and retry settings
                proxy.initiate("{{ call_graph }}::{{ operation }}::first", proxyClient, progress.getResourceModel(), progress.getCallbackContext())

                    // STEP 2.1 [TODO: construct a body of a request]
                    .translateToServiceRequest(Translator::translateToFirstUpdateRequest)

                    // STEP 2.2 [TODO: make an api call]
                    .makeServiceCall((awsRequest, client) -> {
                        AwsResponse awsResponse = null;
                        try {

                            // TODO: put your update resource code here

                        } catch (final AwsServiceException e) {
                            /*
                            * While the handler contract states that the handler must always return a progress event,
                            * you may throw any instance of BaseHandlerException, as the wrapper map it to a progress event.
                            * Each BaseHandlerException maps to a specific error code, and you should map service exceptions as closely as possible
                            * to more specific error codes
                            */
                            throw new CfnGeneralServiceException(ResourceModel.TYPE_NAME, e);
                        }

                        logger.log(String.format("%s has successfully been updated.", ResourceModel.TYPE_NAME));
                        return awsResponse;
                    })

                    // STEP 2.3 [TODO: stabilize step is not necessarily required but typically involves describing the resource until it is in a certain status, though it can take many forms]
                    // stabilization step may or may not be needed after each API call
                    // for more information -> https://docs.aws.amazon.com/cloudformation-cli/latest/userguide/resource-type-test-contract.html
                    .stabilize((awsRequest, awsResponse, client, model, context) -> {
                        // TODO: put your stabilization code here

                        final boolean stabilized = true;

                        logger.log(String.format("%s [%s] update has stabilized: %s", ResourceModel.TYPE_NAME, model.getPrimaryIdentifier(), stabilized));
                        return stabilized;
                    })
                    .progress())

            // If your resource is provisioned through multiple API calls, then the following pattern is required (and might take as many postUpdate callbacks as necessary)
            // STEP 3 [second update/stabilize progress chain]
            .then(progress ->
                    // STEP 3.0 [initialize a proxy context]
                    // If your resource is provisioned through multiple API calls, you will need to apply each subsequent update
                    // step in a discrete call/stabilize chain to ensure the entire resource is provisioned as intended.
                    proxy.initiate("{{ call_graph }}::{{ operation }}::second", proxyClient, progress.getResourceModel(), progress.getCallbackContext())

                    // STEP 3.1 [TODO: construct a body of a request]
                    .translateToServiceRequest(Translator::translateToSecondUpdateRequest)

                    // STEP 3.2 [TODO: make an api call]
                    .makeServiceCall((awsRequest, client) -> {
                        AwsResponse awsResponse = null;
                        try {

                            // TODO: put your post update resource code here

                        } catch (final AwsServiceException e) {
                            /*
                            * While the handler contract states that the handler must always return a progress event,
                            * you may throw any instance of BaseHandlerException, as the wrapper map it to a progress event.
                            * Each BaseHandlerException maps to a specific error code, and you should map service exceptions as closely as possible
                            * to more specific error codes
                            */
                            throw new CfnGeneralServiceException(ResourceModel.TYPE_NAME, e);
                        }

                        logger.log(String.format("%s has successfully been updated.", ResourceModel.TYPE_NAME));
                        return awsResponse;
                    })
                    .progress())

            // If your resource supports tags, then the following pattern is required to handle stack level tags via soft-failing pattern
            // STEP 4 [update stack level tags progress chain]
            .then(progress -> {

                // STEP 4.0 [initialize a proxy context]
                // Stack level tag update should not force user but rather be optional, as it is possible that stack execution role will not have
                // enough permissions to do so
                // step in a discrete call/stabilize chain to ensure the entire resource is provisioned as intended.
                ProgressEvent<ResourceModel, CallbackContext> event = proxy.initiate("{{ call_graph }}::{{ operation }}::stack-level-tags", proxyClient, progress.getResourceModel(), progress.getCallbackContext())

                    // STEP 4.1 [TODO: construct a body of a request]
                    .translateToServiceRequest((model) -> Translator.translateToStackTagUpdateRequest(request.getPreviousResourceTags(), request.getDesiredResourceTags()))

                    // STEP 4.2 [TODO: make an api call]
                    .makeServiceCall((awsRequest, client) -> {
                        AwsResponse awsResponse = null;
                        try {

                            // TODO: put your post update resource code here

                        } catch (final AwsServiceException e) {
                            /*
                            * While the handler contract states that the handler must always return a progress event,
                            * you may throw any instance of BaseHandlerException, as the wrapper map it to a progress event.
                            * Each BaseHandlerException maps to a specific error code, and you should map service exceptions as closely as possible
                            * to more specific error codes
                            */
                            throw new CfnGeneralServiceException(ResourceModel.TYPE_NAME, e);
                        }

                        logger.log(String.format("%s has successfully been updated.", ResourceModel.TYPE_NAME));
                        return awsResponse;
                    })
                    .progress();

                // STEP 4.3 [TODO: check if event 1) failed 2) error code is access denied]
                // if (event.isFailed() && ...) {
                //     return progress;
                // }
                return event;
            })

            // If your resource supports tags, then the following pattern is required to handle resource level tags
            // STEP 5 [update resource level tags progress chain]
            .then(progress ->

                // STEP 5.0 [initialize a proxy context]
                // Resource level tag update should force user to use right set of permissions
                // step in a discrete call/stabilize chain to ensure the entire resource is provisioned as intended.
                proxy.initiate("{{ call_graph }}::{{ operation }}::resource-level-tags", proxyClient, progress.getResourceModel(), progress.getCallbackContext())

                    // STEP 5.1 [TODO: construct a body of a request]
                    .translateToServiceRequest((model) -> Translator.translateToResourceTagUpdateRequest(previousModel, model))

                    // STEP 5.2 [TODO: make an api call]
                    .makeServiceCall((awsRequest, client) -> {
                        AwsResponse awsResponse = null;
                        try {

                            // TODO: put your post update resource code here

                        } catch (final AwsServiceException e) {
                            /*
                            * While the handler contract states that the handler must always return a progress event,
                            * you may throw any instance of BaseHandlerException, as the wrapper map it to a progress event.
                            * Each BaseHandlerException maps to a specific error code, and you should map service exceptions as closely as possible
                            * to more specific error codes
                            */
                            throw new CfnGeneralServiceException(ResourceModel.TYPE_NAME, e);
                        }

                        logger.log(String.format("%s has successfully been updated.", ResourceModel.TYPE_NAME));
                        return awsResponse;
                    })
                    .progress())


            // STEP 6 [TODO: describe call/chain to return the resource model]
            .then(progress -> new ReadHandler().handleRequest(proxy, request, callbackContext, proxyClient, logger));
    }
}
