#!/usr/bin/env bash
pushd `git rev-parse --show-toplevel`
./gradlew :grpc-rls-integration-testing:run -PmainClass=io.grpc.rls.testing.TestLoadBalancer --args="$@"
popd
