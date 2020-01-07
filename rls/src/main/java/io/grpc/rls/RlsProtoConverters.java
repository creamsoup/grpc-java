/*
 * Copyright 2019 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Converter;
import io.grpc.internal.JsonUtil;
import io.grpc.lookup.v1alpha1.RouteLookupRequest;
import io.grpc.lookup.v1alpha1.RouteLookupResponse;
import io.grpc.lookup.v1alpha1.RouteLookupResponse.Header;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.RlsProtoData.Name;
import io.grpc.rls.RlsProtoData.NameMatcher;
import io.grpc.rls.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A RlsProtoConverters is a collection of {@link Converter} between RouteLookupService proto
 * messages to internal representation in {@link RlsProtoData}.
 */
final class RlsProtoConverters {

  /**
   * A RouteLookupRequestConverter converts between {@link RouteLookupRequest} and {@link
   * RlsProtoData.RouteLookupRequest}.
   */
  static final class RouteLookupRequestConverter
      extends Converter<RouteLookupRequest, RlsProtoData.RouteLookupRequest> {

    @Override
    protected RlsProtoData.RouteLookupRequest doForward(RouteLookupRequest routeLookupRequest) {
      return
          new RlsProtoData.RouteLookupRequest(
              routeLookupRequest.getServer(),
              routeLookupRequest.getPath(),
              routeLookupRequest.getTargetType(),
              routeLookupRequest.getKeyMapMap());
    }

    @Override
    protected RouteLookupRequest doBackward(RlsProtoData.RouteLookupRequest routeLookupRequest) {
      return
          RouteLookupRequest.newBuilder()
              .setServer(routeLookupRequest.getServer())
              .setPath(routeLookupRequest.getPath())
              .setTargetType(routeLookupRequest.getTargetType())
              .putAllKeyMap(routeLookupRequest.getKeyMap())
              .build();
    }
  }
  /**
   * A RouteLookupResponseConverter converts between {@link RouteLookupResponse} and {@link
   * RlsProtoData.RouteLookupResponse}.
   */
  static final class RouteLookupResponseConverter
      extends Converter<RouteLookupResponse, RlsProtoData.RouteLookupResponse> {

    @Override
    protected RlsProtoData.RouteLookupResponse doForward(RouteLookupResponse routeLookupResponse) {
      return
          new RlsProtoData.RouteLookupResponse(
              routeLookupResponse.getTarget(),
              new HeadersConverter().convert(routeLookupResponse.getHeadersList()));
    }

    @Override
    protected RouteLookupResponse doBackward(RlsProtoData.RouteLookupResponse routeLookupResponse) {
      return RouteLookupResponse.newBuilder()
          .setTarget(routeLookupResponse.getTarget())
          .addAllHeaders(new HeadersConverter().reverse().convert(routeLookupResponse.getHeaders()))
          .build();
    }
  }

  // Note: it usually not a good idea to converts collections to collection (should use convertAll)
  //  for convenience / performance reason, this class doesn't follow the convention (not exposed).
  private static final class HeadersConverter extends Converter<List<Header>, List<String>> {

    @Override
    protected List<String> doForward(List<Header> headers) {
      List<String> headerList = new ArrayList<>(headers.size() * 2);
      for (Header header : headers) {
        headerList.add(header.getHeader());
        headerList.add(header.getValue());
      }
      return headerList;
    }

    @Override
    protected List<Header> doBackward(List<String> headers) {
      checkArgument(headers.size() % 2 == 0, "Invalid header size: %s", headers.size());

      List<Header> headerList = new ArrayList<>(headers.size() / 2);
      Iterator<String> iter = headers.iterator();
      while (iter.hasNext()) {
        String header = iter.next();
        String value = iter.next();
        headerList.add(Header.newBuilder().setHeader(header).setValue(value).build());
      }
      return headerList;
    }
  }

  // This one will be from ServiceConfig (json)
  static final class RouteLookupConfigConverter
      extends Converter<Map<String, ?>, RouteLookupConfig> {

    @Override
    protected RouteLookupConfig doForward(Map<String, ?> json) {
      List<GrpcKeyBuilder> grpcKeyBuilders =
          GrpcKeyBuilderConverter
              .covertAll(JsonUtil.checkObjectList(JsonUtil.getList(json, "grpcKeyBuilders")));
      String lookupService = JsonUtil.getString(json, "lookupService");
      long timeout = JsonUtil.getDouble(json, "lookupServiceTimeout").longValue();
      long maxAge = JsonUtil.getDouble(json, "maxAge").longValue();
      long staleAge = JsonUtil.getDouble(json, "staleAge").longValue();
      long cacheSize = JsonUtil.getDouble(json, "cacheSize").longValue();
      String defaultTarget = JsonUtil.getString(json, "defaultTarget");
      RequestProcessingStrategy strategy =
          RequestProcessingStrategy
              .valueOf(JsonUtil.getString(json, "requestProcessingStrategy").toUpperCase());
      return new RouteLookupConfig(
          grpcKeyBuilders,
          lookupService,
          timeout,
          maxAge,
          staleAge,
          cacheSize,
          defaultTarget,
          strategy);
    }

    @Override
    protected Map<String, Object> doBackward(RouteLookupConfig routeLookupConfig) {
      return null;
    }
  }

  private static final class GrpcKeyBuilderConverter {
    public static List<GrpcKeyBuilder> covertAll(List<Map<String, ?>> keyBuilders) {
      List<GrpcKeyBuilder> keyBuilderList = new ArrayList<>();
      for (Map<String, ?> keyBuilder : keyBuilders) {
        keyBuilderList.add(convert(keyBuilder));
      }
      return keyBuilderList;
    }

    @SuppressWarnings("unchecked")
    public static GrpcKeyBuilder convert(Map<String, ?> keyBuilder) {
      List<Map<String, ?>> rawNames =
          JsonUtil.checkObjectList(JsonUtil.getList(keyBuilder, "names"));
      List<Name> names = new ArrayList<>();
      for (Map<String, ?> rawName : rawNames) {
        names.add(
            new Name(
                JsonUtil.getString(rawName, "service"), JsonUtil.getString(rawName, "method")));
      }
      Map<String, ?> rawHeaders = JsonUtil.getObject(keyBuilder, "headers");
      Map<String, NameMatcher> nameMatcherMap = new HashMap<>();
      for (Map.Entry<String, ?> rawHeader : rawHeaders.entrySet()) {
        NameMatcher matcher = new NameMatcher((List<String>) rawHeader.getValue());
        nameMatcherMap.put(rawHeader.getKey(), matcher);
      }
      return new GrpcKeyBuilder(names, nameMatcherMap);
    }
  }
}
