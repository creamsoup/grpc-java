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
import io.grpc.lookup.v1alpha1.RouteLookupRequest;
import io.grpc.lookup.v1alpha1.RouteLookupResponse;
import io.grpc.lookup.v1alpha1.RouteLookupResponse.Header;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
}
