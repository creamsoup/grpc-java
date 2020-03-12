#!/usr/bin/env bash
TARGET="directpath-bigtable.googleapis.com:443"
HEADER_DATA="some_random_header_i_do_not_know_what_it_should_be"
./../gradlew run -PmainClass=io.grpc.rls.testing.ConstantRlsServer --args="$TARGET $HEADER_DATA"