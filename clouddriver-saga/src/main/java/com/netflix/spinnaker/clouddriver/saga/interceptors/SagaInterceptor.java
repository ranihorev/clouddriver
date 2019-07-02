/*
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.clouddriver.saga.interceptors;

import com.netflix.spinnaker.clouddriver.saga.SagaResult;
import com.netflix.spinnaker.clouddriver.saga.model.Saga;
import com.netflix.spinnaker.clouddriver.saga.model.SagaStep;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.core.Ordered;

public interface SagaInterceptor extends Ordered {

  default boolean shouldSkipStep(@Nonnull SagaStep sagaStep) {
    return false;
  }

  default boolean shouldFailSaga(@Nonnull Saga saga, @Nonnull SagaStep sagaStep) {
    return false;
  }

  default void afterProcessingStep(
      @Nonnull Saga saga, @Nonnull SagaStep sagaStep, @Nullable SagaResult<?> stepResult) {}
}
