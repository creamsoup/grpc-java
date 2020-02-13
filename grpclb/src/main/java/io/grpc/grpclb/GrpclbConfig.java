package io.grpc.grpclb;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import io.grpc.grpclb.GrpclbState.Mode;
import javax.annotation.Nullable;

final class GrpclbConfig  {

  private final Mode mode;
  @Nullable
  private final String target;

  private GrpclbConfig(Mode mode, @Nullable String target) {
    System.out.println("grpclb config, mode: " + mode + " target: " + target);
    this.mode = checkNotNull(mode, "mode");
    this.target = target;
  }

  static GrpclbConfig create(Mode mode) {
    return create(mode, null);
  }

  static GrpclbConfig create(Mode mode, @Nullable String targetName) {
    return new GrpclbConfig(mode, targetName);
  }

  Mode getMode() {
    return mode;
  }

  /**
   * If specified, it overrides the name of the target to be sent to the balancer. if not, the
   * target to be sent to the balancer will continue to be obtained from the target URI passed
   * to the gRPC client channel.
   */
  @Nullable
  String getTarget() {
    return target;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    GrpclbConfig that = (GrpclbConfig) o;
    return mode == that.mode && Objects.equal(target, that.target);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(mode, target);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("mode", mode)
        .add("target", target)
        .toString();
  }
}