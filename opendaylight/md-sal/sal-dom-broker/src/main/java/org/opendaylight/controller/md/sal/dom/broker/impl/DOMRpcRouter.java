/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.md.sal.dom.broker.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcAvailabilityListener;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcException;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcIdentifier;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcImplementation;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcImplementationNotAvailableException;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcImplementationRegistration;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcProviderService;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcResult;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcService;
import org.opendaylight.controller.md.sal.dom.api.DefaultDOMRpcException;
import org.opendaylight.controller.md.sal.dom.spi.AbstractDOMRpcImplementationRegistration;
import org.opendaylight.controller.md.sal.dom.spi.DefaultDOMRpcResult;
import org.opendaylight.mdsal.common.api.MappingCheckedFuture;
import org.opendaylight.yangtools.concepts.AbstractListenerRegistration;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.util.concurrent.ExceptionMapper;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaContextListener;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;

public final class DOMRpcRouter implements AutoCloseable, DOMRpcService, DOMRpcProviderService, SchemaContextListener {
    private static final ExceptionMapper<org.opendaylight.mdsal.dom.api.DOMRpcException> MDSAL_DOM_RPC_EX_MAPPER =
            new ExceptionMapper<org.opendaylight.mdsal.dom.api.DOMRpcException>(
                    "rpc", org.opendaylight.mdsal.dom.api.DOMRpcException.class) {
        @Override
        protected org.opendaylight.mdsal.dom.api.DOMRpcException newWithCause(String message, Throwable cause) {
            return cause instanceof org.opendaylight.mdsal.dom.api.DOMRpcException
                    ? (org.opendaylight.mdsal.dom.api.DOMRpcException)cause
                            : new org.opendaylight.mdsal.dom.api.DefaultDOMRpcException("RPC failed", cause);
        }
    };

    private static final ExceptionMapper<DOMRpcException> LEGACY_DOM_RPC_EX_MAPPER =
            new ExceptionMapper<DOMRpcException>("rpc", DOMRpcException.class) {
        @Override
        protected DOMRpcException newWithCause(String message, Throwable cause) {
            return cause instanceof DOMRpcException ? (DOMRpcException)cause
                : cause instanceof org.opendaylight.mdsal.dom.api.DOMRpcImplementationNotAvailableException
                    ? new DOMRpcImplementationNotAvailableException(cause.getMessage(), cause.getCause())
                        : new DefaultDOMRpcException("RPC failed", cause);
        }
    };

    // This mapping is used to translate mdsal DOMRpcImplementations to their corresponding legacy
    // DOMRpcImplementations registered thru this interface when invoking a DOMRpcAvailabilityListener.
    private final Map<org.opendaylight.mdsal.dom.api.DOMRpcImplementation, DOMRpcImplementation> implMapping =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final org.opendaylight.mdsal.dom.api.DOMRpcService delegateRpcService;
    private final org.opendaylight.mdsal.dom.api.DOMRpcProviderService delegateRpcProviderService;

    @VisibleForTesting
    public DOMRpcRouter() {
        org.opendaylight.mdsal.dom.broker.DOMRpcRouter delegate = new org.opendaylight.mdsal.dom.broker.DOMRpcRouter();
        this.delegateRpcService = delegate;
        this.delegateRpcProviderService = delegate;
    }

    public DOMRpcRouter(final org.opendaylight.mdsal.dom.api.DOMRpcService delegateRpcService,
            final org.opendaylight.mdsal.dom.api.DOMRpcProviderService delegateRpcProviderService) {
        this.delegateRpcService = delegateRpcService;
        this.delegateRpcProviderService = delegateRpcProviderService;
    }

    @Override
    public <T extends DOMRpcImplementation> DOMRpcImplementationRegistration<T> registerRpcImplementation(
            final T implementation, final DOMRpcIdentifier... rpcs) {
        return registerRpcImplementation(implementation, ImmutableSet.copyOf(rpcs));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public synchronized <T extends DOMRpcImplementation> DOMRpcImplementationRegistration<T> registerRpcImplementation(
            final T implementation, final Set<DOMRpcIdentifier> rpcs) {
        org.opendaylight.mdsal.dom.api.DOMRpcImplementation delegateImpl =
            new org.opendaylight.mdsal.dom.api.DOMRpcImplementation() {
                @Override
                public CheckedFuture<org.opendaylight.mdsal.dom.api.DOMRpcResult,
                        org.opendaylight.mdsal.dom.api.DOMRpcException> invokeRpc(
                        org.opendaylight.mdsal.dom.api.DOMRpcIdentifier rpc, NormalizedNode<?, ?> input) {
                    final ListenableFuture future = implementation.invokeRpc(convert(rpc), input);
                    return MappingCheckedFuture.create(future, MDSAL_DOM_RPC_EX_MAPPER);
                }

                @Override
                public long invocationCost() {
                    return implementation.invocationCost();
                }
            };

        implMapping.put(delegateImpl, implementation);

        final org.opendaylight.mdsal.dom.api.DOMRpcImplementationRegistration
            <org.opendaylight.mdsal.dom.api.DOMRpcImplementation> reg = delegateRpcProviderService
                .registerRpcImplementation(delegateImpl,
                        rpcs.stream().map(DOMRpcRouter::convert).collect(Collectors.toSet()));

        return new AbstractDOMRpcImplementationRegistration<T>(implementation) {
            @Override
            protected void removeRegistration() {
                reg.close();
                implMapping.remove(delegateImpl);
            }
        };
    }

    private static org.opendaylight.mdsal.dom.api.DOMRpcIdentifier convert(DOMRpcIdentifier from) {
        return org.opendaylight.mdsal.dom.api.DOMRpcIdentifier.create(from.getType(), from.getContextReference());
    }

    private static DOMRpcIdentifier convert(org.opendaylight.mdsal.dom.api.DOMRpcIdentifier from) {
        return DOMRpcIdentifier.create(from.getType(), from.getContextReference());
    }

    private static ListenableFuture<DOMRpcResult> convert(org.opendaylight.mdsal.dom.api.DOMRpcResult from) {
        return from == null ? Futures.immediateFuture(null)
                : from instanceof DOMRpcResult ? Futures.immediateFuture((DOMRpcResult)from)
                        : Futures.immediateFuture(new DefaultDOMRpcResult(from.getResult(), from.getErrors()));
    }

    private static Collection<DOMRpcIdentifier> convert(
            Collection<org.opendaylight.mdsal.dom.api.DOMRpcIdentifier> from) {
        return from.stream().map(DOMRpcRouter::convert).collect(Collectors.toList());
    }

    @Override
    public CheckedFuture<DOMRpcResult, DOMRpcException> invokeRpc(final SchemaPath type,
                                                                  final NormalizedNode<?, ?> input) {
        final ListenableFuture<DOMRpcResult> future = Futures.transformAsync(delegateRpcService.invokeRpc(type, input),
                DOMRpcRouter::convert, MoreExecutors.directExecutor());
        return MappingCheckedFuture.create(future, LEGACY_DOM_RPC_EX_MAPPER);
    }

    @Override
    public synchronized <T extends DOMRpcAvailabilityListener> ListenerRegistration<T> registerRpcListener(
            final T listener) {
        final ListenerRegistration<org.opendaylight.mdsal.dom.api.DOMRpcAvailabilityListener> reg =
            delegateRpcService.registerRpcListener(new org.opendaylight.mdsal.dom.api.DOMRpcAvailabilityListener() {
                @Override
                public void onRpcAvailable(Collection<org.opendaylight.mdsal.dom.api.DOMRpcIdentifier> rpcs) {
                    listener.onRpcAvailable(convert(rpcs));
                }

                @Override
                public void onRpcUnavailable(Collection<org.opendaylight.mdsal.dom.api.DOMRpcIdentifier> rpcs) {
                    listener.onRpcUnavailable(convert(rpcs));
                }

                @Override
                public boolean acceptsImplementation(final org.opendaylight.mdsal.dom.api.DOMRpcImplementation impl) {
                    // If the DOMRpcImplementation wasn't registered thru this interface then the mapping won't be
                    // present - in this we can't call the listener so just assume acceptance which is the default
                    // behavior. This should be fine since a legacy listener would not be aware of implementation types
                    // registered via the new mdsal API.
                    final DOMRpcImplementation legacyImpl = implMapping.get(impl);
                    return legacyImpl != null ? listener.acceptsImplementation(legacyImpl) : true;
                }
            });

        return new AbstractListenerRegistration<T>(listener) {
            @Override
            protected void removeRegistration() {
                reg.close();
            }
        };
    }

    @Override
    public void close() {
    }

    @Override
    @VisibleForTesting
    public void onGlobalContextUpdated(final SchemaContext context) {
        if (delegateRpcService instanceof SchemaContextListener) {
            ((SchemaContextListener)delegateRpcService).onGlobalContextUpdated(context);
        }
    }
}
