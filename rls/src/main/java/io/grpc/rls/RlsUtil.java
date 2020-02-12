package io.grpc.rls;

import io.grpc.EquivalentAddressGroup;
import java.net.InetSocketAddress;

public class RlsUtil {

  static EquivalentAddressGroup createEag(String target) {
    int index = target.indexOf(':');
    if (index < 0) {
      //TODO or 443
      return new EquivalentAddressGroup(new InetSocketAddress(target, 80));
    }
    return
        new EquivalentAddressGroup(
            new InetSocketAddress(
                target.substring(0, index),
                Integer.parseInt(target.substring(index + 1))));
  }

  private RlsUtil() {
  }
}
