/*
 *  Copyright (c) 2023 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - Initial implementation
 *
 */

package org.eclipse.edc.connector.transfer.command.handlers;

import org.eclipse.edc.connector.transfer.observe.TransferProcessObservableImpl;
import org.eclipse.edc.connector.transfer.spi.observe.TransferProcessListener;
import org.eclipse.edc.connector.transfer.spi.observe.TransferProcessObservable;
import org.eclipse.edc.connector.transfer.spi.store.TransferProcessStore;
import org.eclipse.edc.connector.transfer.spi.types.TransferProcess;
import org.eclipse.edc.connector.transfer.spi.types.TransferProcessStates;
import org.eclipse.edc.connector.transfer.spi.types.command.TerminateTransferCommand;
import org.eclipse.edc.spi.EdcException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.edc.connector.transfer.spi.types.TransferProcessStates.STARTED;
import static org.eclipse.edc.connector.transfer.spi.types.TransferProcessStates.TERMINATING;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TerminateTransferCommandHandlerTest {

    private final TransferProcessStore store = mock(TransferProcessStore.class);
    private final TransferProcessObservable observable = new TransferProcessObservableImpl();
    private final TransferProcessListener listener = mock(TransferProcessListener.class);
    private TerminateTransferCommandHandler handler;

    @BeforeEach
    void setUp() {
        observable.registerListener(listener);
        handler = new TerminateTransferCommandHandler(store);
    }

    @Test
    void verifyCorrectType() {
        assertThat(handler.getType()).isEqualTo(TerminateTransferCommand.class);
    }

    @Test
    void handle() {
        var cmd = new TerminateTransferCommand("test-id", "a reason");
        var tp = TransferProcess.Builder.newInstance().id("test-id").state(STARTED.code())
                .updatedAt(124123) //some invalid time
                .type(TransferProcess.Type.CONSUMER).build();
        var originalDate = tp.getUpdatedAt();

        when(store.find(anyString())).thenReturn(tp);
        handler.handle(cmd);

        assertThat(tp.getState()).isEqualTo(TERMINATING.code());
        assertThat(tp.getErrorDetail()).isEqualTo("a reason");
        assertThat(tp.getUpdatedAt()).isNotEqualTo(originalDate);

        verify(store).find(anyString());
        verify(store).save(tp);
        verifyNoMoreInteractions(store);
    }

    @ParameterizedTest
    @EnumSource(value = TransferProcessStates.class, names = { "COMPLETED", "TERMINATED" })
    void handle_illegalState(TransferProcessStates targetState) {
        var tp = TransferProcess.Builder.newInstance().id("test-id").state(targetState.code())
                .type(TransferProcess.Type.CONSUMER).build();
        var originalDate = tp.getUpdatedAt();
        var cmd = new TerminateTransferCommand("test-id", "a reason");

        when(store.find(anyString())).thenReturn(tp);
        handler.handle(cmd);

        assertThat(tp.getUpdatedAt()).isEqualTo(originalDate);

        verify(store).find(anyString());
        verifyNoMoreInteractions(store);
        verifyNoInteractions(listener);
    }

    @Test
    void handle_notFound() {
        var cmd = new TerminateTransferCommand("test-id", "a reason");

        when(store.find(anyString())).thenReturn(null);
        assertThatThrownBy(() -> handler.handle(cmd)).isInstanceOf(EdcException.class).hasMessageStartingWith("Could not find TransferProcess with ID [test-id]");
        verifyNoInteractions(listener);
    }
}
