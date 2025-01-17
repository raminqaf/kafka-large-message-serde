/*
 * MIT License
 *
 * Copyright (c) 2021 bakdata
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.bakdata.kafka;

import static com.bakdata.kafka.LargeMessageRetrievingClient.getBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.io.UncheckedIOException;
import java.util.Map;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class LargeMessageStoringClientTest {

    private static final String TOPIC = "output";
    private static final Deserializer<String> STRING_DESERIALIZER = Serdes.String().deserializer();
    private static final Serializer<String> STRING_SERIALIZER = Serdes.String().serializer();
    @Mock
    IdGenerator idGenerator;
    @Mock
    BlobStorageClient client;

    private static void expectNonBackedText(final String expected, final byte[] backedText) {
        assertThat(STRING_DESERIALIZER.deserialize(null, getBytes(backedText)))
                .isInstanceOf(String.class)
                .isEqualTo(expected);
    }

    private static byte[] serialize(String s) {
        return STRING_SERIALIZER.serialize(null, s);
    }

    private static LargeMessageStoringClient createStorer(final Map<String, Object> properties) {
        final AbstractLargeMessageConfig config = new AbstractLargeMessageConfig(properties);
        return config.getStorer();
    }

    private LargeMessageStoringClient createStorer(final int maxSize) {
        return this.createStorer(maxSize, null);
    }

    private LargeMessageStoringClient createStorer(final int maxSize, final BlobStorageURI basePath) {
        return this.createStorer(maxSize, basePath, this.idGenerator);
    }

    private LargeMessageStoringClient createStorer(final int maxSize, final BlobStorageURI basePath,
            final IdGenerator idGenerator) {
        return LargeMessageStoringClient.builder()
                .client(this.client)
                .basePath(basePath)
                .maxSize(maxSize)
                .idGenerator(idGenerator)
                .build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldWriteNonBackedText(final boolean isKey) {
        final LargeMessageStoringClient storer = this.createStorer(Integer.MAX_VALUE);
        assertThat(storer.storeBytes(null, serialize("foo"), isKey))
                .satisfies(backedText -> expectNonBackedText("foo", backedText));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldWriteNonBackedNull(final boolean isKey) {
        final LargeMessageStoringClient storer = this.createStorer(Integer.MAX_VALUE);
        assertThat(storer.storeBytes(null, null, isKey))
                .isNull();
    }

    @Test
    void shouldWriteBackedTextKey() {
        final String bucket = "bucket";
        final String basePath = "foo://" + bucket + "/base/";
        when(this.idGenerator.generateId(serialize("foo"))).thenReturn("key");
        when(this.client.putObject(serialize("foo"), bucket, "base/" + TOPIC + "/keys/key"))
                .thenReturn("uri");
        final LargeMessageStoringClient storer = this.createStorer(0, BlobStorageURI.create(basePath));
        assertThat(storer.storeBytes(TOPIC, serialize("foo"), true))
                .isEqualTo(LargeMessageStoringClient.serialize("uri"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldWriteBackedNull(final boolean isKey) {
        final LargeMessageStoringClient storer = this.createStorer(0);
        assertThat(storer.storeBytes(null, null, isKey))
                .isNull();
    }

    @Test
    void shouldWriteBackedTextValue() {
        final String bucket = "bucket";
        final String basePath = "foo://" + bucket + "/base/";
        when(this.idGenerator.generateId(serialize("foo"))).thenReturn("key");
        when(this.client.putObject(serialize("foo"), bucket, "base/" + TOPIC + "/values/key"))
                .thenReturn("uri");
        final LargeMessageStoringClient storer = this.createStorer(0, BlobStorageURI.create(basePath));
        assertThat(storer.storeBytes(TOPIC, serialize("foo"), false))
                .isEqualTo(LargeMessageStoringClient.serialize("uri"));
    }

    @Test
    void shouldDeleteFiles() {
        final String bucket = "bucket";
        final String basePath = "foo://" + bucket + "/base/";
        final LargeMessageStoringClient storer = this.createStorer(0, BlobStorageURI.create(basePath));
        storer.deleteAllFiles(TOPIC);
        verify(this.client).deleteAllObjects(bucket, "base/" + TOPIC + "/");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldThrowExceptionOnError(final boolean isKey) {
        final String bucket = "bucket";
        final String basePath = "foo://" + bucket + "/base/";
        when(this.idGenerator.generateId(any())).thenReturn("key");
        when(this.client.putObject(any(), eq(bucket), any())).thenThrow(UncheckedIOException.class);
        final LargeMessageStoringClient storer = this.createStorer(0, BlobStorageURI.create(basePath));
        final byte[] foo = serialize("foo");
        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> storer.storeBytes(TOPIC, foo, isKey));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldThrowExceptionOnNullTopic(final boolean isKey) {
        final String bucket = "bucket";
        final String basePath = "foo://" + bucket + "/base/";
        final LargeMessageStoringClient storer = this.createStorer(0, BlobStorageURI.create(basePath));
        final byte[] foo = serialize("foo");
        assertThatNullPointerException()
                .isThrownBy(() -> storer.storeBytes(null, foo, isKey))
                .withMessage("Topic must not be null");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldThrowExceptionOnNullBasePath(final boolean isKey) {
        final LargeMessageStoringClient storer = this.createStorer(0, null);
        final byte[] foo = serialize("foo");
        assertThatNullPointerException()
                .isThrownBy(() -> storer.storeBytes(TOPIC, foo, isKey))
                .withMessage("Base path must not be null");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldThrowExceptionOnNullIdGenerator(final boolean isKey) {
        final String bucket = "bucket";
        final String basePath = "foo://" + bucket + "/base/";
        final LargeMessageStoringClient storer = this.createStorer(0, BlobStorageURI.create(basePath), null);
        final byte[] foo = serialize("foo");
        assertThatNullPointerException()
                .isThrownBy(() -> storer.storeBytes(TOPIC, foo, isKey))
                .withMessage("Id generator must not be null");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldWriteNonBackedTextWithConfig(final boolean isKey) {
        final Map<String, Object> properties = ImmutableMap.<String, Object>builder()
                .put(AbstractLargeMessageConfig.MAX_BYTE_SIZE_CONFIG, Integer.MAX_VALUE)
                .build();
        final LargeMessageStoringClient storer = createStorer(properties);
        assertThat(storer.storeBytes(null, serialize("foo"), isKey))
                .satisfies(backedText -> expectNonBackedText("foo", backedText));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldWriteBackedTextWithConfig(final boolean isKey) {
        final Map<String, Object> properties = ImmutableMap.<String, Object>builder()
                .put(AbstractLargeMessageConfig.MAX_BYTE_SIZE_CONFIG, 0)
                .build();
        final LargeMessageStoringClient storer = createStorer(properties);
        final byte[] foo = serialize("foo");
        assertThatNullPointerException()
                .isThrownBy(() -> storer.storeBytes(TOPIC, foo, isKey))
                .withMessage("Base path must not be null");
    }
}