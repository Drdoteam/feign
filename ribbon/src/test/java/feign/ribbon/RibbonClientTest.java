/*
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feign.ribbon;

import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;
import com.google.mockwebserver.SocketPolicy;
import dagger.Provides;
import feign.Client;
import feign.Feign;
import feign.RequestLine;
import feign.codec.Decoder;
import feign.codec.Encoder;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URL;

import static com.netflix.config.ConfigurationManager.getConfigInstance;
import static feign.Util.UTF_8;
import static org.testng.Assert.assertEquals;

import javax.inject.Named;

@Test
public class RibbonClientTest {
  interface TestInterface {
    @RequestLine("POST /") void post();
	@RequestLine("GET /?a={a}") void getWithQueryParameters(@Named("a") String a);

    @dagger.Module(injects = Feign.class, overrides = true, addsTo = Feign.Defaults.class)
    static class Module {
      @Provides Decoder defaultDecoder() {
        return new Decoder.Default();
      }

      @Provides Encoder defaultEncoder() {
        return new Encoder.Default();
      }
    }
  }

  @Test
  public void loadBalancingDefaultPolicyRoundRobin() throws IOException, InterruptedException {
    String client = "RibbonClientTest-loadBalancingDefaultPolicyRoundRobin";
    String serverListKey = client + ".ribbon.listOfServers";

    MockWebServer server1 = new MockWebServer();
    server1.enqueue(new MockResponse().setBody("success!".getBytes(UTF_8)));
    server1.play();
    MockWebServer server2 = new MockWebServer();
    server2.enqueue(new MockResponse().setBody("success!".getBytes(UTF_8)));
    server2.play();

    getConfigInstance().setProperty(serverListKey, hostAndPort(server1.getUrl("")) + "," + hostAndPort(server2.getUrl("")));

    try {

      TestInterface api = Feign.create(TestInterface.class, "http://" + client, new TestInterface.Module(), new RibbonModule());

      api.post();
      api.post();

      assertEquals(server1.getRequestCount(), 1);
      assertEquals(server2.getRequestCount(), 1);
      // TODO: verify ribbon stats match
      // assertEquals(target.lb().getLoadBalancerStats().getSingleServerStat())
    } finally {
      server1.shutdown();
      server2.shutdown();
      getConfigInstance().clearProperty(serverListKey);
    }
  }

  @Test
  public void ioExceptionRetry() throws IOException, InterruptedException {
    String client = "RibbonClientTest-ioExceptionRetry";
    String serverListKey = client + ".ribbon.listOfServers";

    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    server.enqueue(new MockResponse().setBody("success!".getBytes(UTF_8)));
    server.play();

    getConfigInstance().setProperty(serverListKey, hostAndPort(server.getUrl("")));

    try {

      TestInterface api = Feign.create(TestInterface.class, "http://" + client, new TestInterface.Module(), new RibbonModule());

      api.post();

      assertEquals(server.getRequestCount(), 2);
      // TODO: verify ribbon stats match
      // assertEquals(target.lb().getLoadBalancerStats().getSingleServerStat())
    } finally {
      server.shutdown();
      getConfigInstance().clearProperty(serverListKey);
    }
  }

	/*
		This test-case replicates a bug that occurs when using RibbonRequest with a query string.

		The querystrings would not be URL-encoded, leading to invalid HTTP-requests if the query string contained
		invalid characters (ex. space).
	 */
	@Test public void urlEncodeQueryStringParameters () throws IOException, InterruptedException {
		String client = "RibbonClientTest-urlEncodeQueryStringParameters";
		String serverListKey = client + ".ribbon.listOfServers";

		String queryStringValue = "some string with space";
		String expectedQueryStringValue = "some+string+with+space";
		String expectedRequestLine = String.format("GET /?a=%s HTTP/1.1", expectedQueryStringValue);

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setBody("success!".getBytes(UTF_8)));
		server.play();

		getConfigInstance().setProperty(serverListKey, hostAndPort(server.getUrl("")));

		try {

			TestInterface api = Feign.create(TestInterface.class, "http://" + client, new TestInterface.Module(), new RibbonModule());

			api.getWithQueryParameters(queryStringValue);

			final String recordedRequestLine = server.takeRequest().getRequestLine();

			assertEquals(recordedRequestLine, expectedRequestLine);
		} finally {
			server.shutdown();
			getConfigInstance().clearProperty(serverListKey);
		}
	}


    @Test
    public void ioExceptionRetryWithBuilder() throws IOException, InterruptedException {
      String client = "RibbonClientTest-ioExceptionRetryWithBuilder";
      String serverListKey = client + ".ribbon.listOfServers";

      MockWebServer server = new MockWebServer();
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
      server.enqueue(new MockResponse().setBody("success!".getBytes(UTF_8)));
      server.play();

      getConfigInstance().setProperty(serverListKey, hostAndPort(server.getUrl("")));

      try {

        TestInterface api = Feign.builder().
                client(new RibbonClient()).
                target(TestInterface.class, "http://" + client);

        api.post();

        assertEquals(server.getRequestCount(), 2);
        // TODO: verify ribbon stats match
        // assertEquals(target.lb().getLoadBalancerStats().getSingleServerStat())
      } finally {
        server.shutdown();
        getConfigInstance().clearProperty(serverListKey);
      }
    }


  static String hostAndPort(URL url) {
    // our build slaves have underscores in their hostnames which aren't permitted by ribbon
    return "localhost:" + url.getPort();
  }
}
