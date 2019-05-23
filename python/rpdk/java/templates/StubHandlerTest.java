package {{ package_name }};

import com.aws.cfn.proxy.AmazonWebServicesClientProxy;
import com.aws.cfn.proxy.AmazonWebServicesRequestFunctionV2;
import com.aws.cfn.proxy.Logger;
import com.aws.cfn.proxy.OperationStatus;
import com.aws.cfn.proxy.ProgressEvent;
import com.aws.cfn.proxy.ResourceHandlerRequest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class {{ operation }}HandlerTest {

    @Mock
    private AmazonWebServicesClientProxy proxy;

    @Mock
    private Logger logger;

    @Before
    public void setup() {
        proxy = mock(AmazonWebServicesClientProxy.class);
        logger = mock(Logger.class);
    }

    @Test
    public void test_HandleRequest_SimpleSuccess() {
        final {{ operation }}Handler handler = new {{ operation }}Handler();

        final {{ pojo_name }} model = {{ pojo_name }}.builder().build();

        final ResourceHandlerRequest<ResourceModel> request = ResourceHandlerRequest.<{{ pojo_name }}>builder()
            .desiredResourceState(model)
            .build();

        final ProgressEvent response = handler.handleRequest(proxy, request, null, logger);

        assertThat(response, is(not(nullValue())));
        assertThat(response.getStatus(), is(equalTo(OperationStatus.SUCCESS)));
        assertThat(response.getResourceModel(), is(equalTo(request.getDesiredResourceState())));
    }
}
