#!/usr/bin/env bash
./../gradlew run -PmainClass=io.grpc.rls.testing.TestLoadBalancer --args="$@"
