// This is a generated file. Modifications will be overwritten.
package {{ package_name }};

import com.aws.cfn.proxy.Logger;
import com.aws.cfn.proxy.ProgressEvent;
import com.aws.cfn.proxy.ResourceHandlerRequest;
import org.json.JSONObject;

public abstract class BaseHandler {

    public abstract ProgressEvent handleRequest(
        final ResourceHandlerRequest<{{ pojo_name }}> request,
        final JSONObject callbackContext,
        final Logger logger);

}
