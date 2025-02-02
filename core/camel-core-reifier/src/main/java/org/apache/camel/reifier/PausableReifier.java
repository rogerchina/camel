/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.camel.reifier;

import java.util.function.Predicate;

import org.apache.camel.ConsumerListener;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.model.PausableDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.processor.PausableProcessor;
import org.apache.camel.util.ObjectHelper;

public class PausableReifier extends ProcessorReifier<PausableDefinition> {

    public PausableReifier(Route route, ProcessorDefinition<?> definition) {
        super(route, PausableDefinition.class.cast(definition));
    }

    @Override
    public Processor createProcessor() throws Exception {
        Processor childProcessor = createChildProcessor(false);

        ConsumerListener<?, ?> consumerListener = resolveConsumerListener();
        ObjectHelper.notNull(consumerListener, "consumerListener");

        route.setConsumerListener(consumerListener);

        return new PausableProcessor(consumerListener, childProcessor);
    }

    protected ConsumerListener<?, ?> resolveConsumerListener() {
        ConsumerListener<?, ?> consumerListener = definition.getConsumerListenerBean();
        if (consumerListener == null) {
            String ref = definition.getConsumerListener();

            consumerListener = mandatoryLookup(ref, ConsumerListener.class);
        }

        Predicate<?> supplier = resolveUntilCheck();
        consumerListener.setResumableCheck(supplier);
        return consumerListener;
    }

    protected Predicate<?> resolveUntilCheck() {
        Predicate<?> supplier = definition.getUntilCheckBean();
        if (supplier == null) {
            String ref = definition.getUntilCheck();

            supplier = mandatoryLookup(ref, Predicate.class);
        }

        return supplier;
    }
}
