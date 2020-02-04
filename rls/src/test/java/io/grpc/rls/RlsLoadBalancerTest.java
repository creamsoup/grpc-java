/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.rls;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.JsonParser;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class RlsLoadBalancerTest {

  private final LoadBalancerProvider provider = new RlsLoadBalancerProvider();
  private Helper helper = mock(Helper.class);
  private LoadBalancer rlslb;

  private static final String TEST_RLS_CONFIG = "{\n"
      + "  \"routeLookupConfig\": {\n"
      + "    \"grpcKeyBuilders\": [\n"
      + "      {\n"
      + "        \"names\": [\n"
      + "          {\n"
      + "            \"service\": \"service1\",\n"
      + "            \"method\": \"create\"\n"
      + "          }\n"
      + "        ],\n"
      + "        \"headers\": {\n"
      + "          \"user\": {\n"
      + "            \"names\": [\n"
      + "              \"User\",\n"
      + "              \"Parent\"\n"
      + "            ],\n"
      + "            \"optional\": false\n"
      + "          },\n"
      + "          \"id\": {\n"
      + "            \"names\": [\n"
      + "              \"X-Google-Id\"\n"
      + "            ],\n"
      + "            \"optional\": true\n"
      + "          }\n"
      + "        }\n"
      + "      },\n"
      + "      {\n"
      + "        \"names\": [\n"
      + "          {\n"
      + "            \"service\": \"service1\",\n"
      + "            \"method\": \"*\"\n"
      + "          }\n"
      + "        ],\n"
      + "        \"headers\": {\n"
      + "          \"user\": {\n"
      + "            \"names\": [\n"
      + "              \"User\",\n"
      + "              \"Parent\"\n"
      + "            ],\n"
      + "            \"optional\": false\n"
      + "          },\n"
      + "          \"password\": {\n"
      + "            \"names\": [\n"
      + "              \"Password\"\n"
      + "            ],\n"
      + "            \"optional\": true\n"
      + "          }\n"
      + "        }\n"
      + "      },\n"
      + "      {\n"
      + "        \"names\": [\n"
      + "          {\n"
      + "            \"service\": \"service3\",\n"
      + "            \"method\": \"*\"\n"
      + "          }\n"
      + "        ],\n"
      + "        \"headers\": {\n"
      + "          \"user\": {\n"
      + "            \"names\": [\n"
      + "              \"User\",\n"
      + "              \"Parent\"\n"
      + "            ],\n"
      + "            \"optional\": false\n"
      + "          }\n"
      + "        }\n"
      + "      }\n"
      + "    ],\n"
      + "    \"lookupService\": \"service1\",\n"
      + "    \"lookupServiceTimeout\": 2,\n"
      + "    \"maxAge\": 300,\n"
      + "    \"staleAge\": 240,\n"
      + "    \"cacheSize\": 1000,\n"
      + "    \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\",\n"
      + "    \"requestProcessingStrategy\": \"ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS\"\n"
      + "  },\n"
      + "  \"childPolicy\": [\n"
      + "    {\n"
      + "      \"grpclb\": {}\n"
      + "    }\n"
      + "  ],\n"
      + "  \"childPolicyConfigTargetFieldName\": \"target\"\n"
      + "}";
  @Mock
  private ChannelLogger channelLogger;

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final FakeClock fakeClock = new FakeClock();

  @Before
  public void setUp() throws Exception {
    doReturn(channelLogger).when(helper).getChannelLogger();
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void name() throws IOException {
    rlslb = provider.newLoadBalancer(helper);
    ConfigOrError policy = provider
        .parseLoadBalancingPolicyConfig((Map<String, ?>) JsonParser.parse(TEST_RLS_CONFIG));
    assertThat(policy.getConfig()).isNotNull();
    SocketAddress rlsServerAddress = mock(SocketAddress.class);
    rlslb.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.of(new EquivalentAddressGroup(rlsServerAddress)))
        .setAttributes(Attributes.EMPTY)
        .setLoadBalancingPolicyConfig(policy.getConfig()).build());

  }
}