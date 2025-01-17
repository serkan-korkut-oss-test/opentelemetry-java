/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.internal;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.proto.collector.trace.v1.internal.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.internal.InstrumentationLibrarySpans;
import io.opentelemetry.proto.trace.v1.internal.ResourceSpans;
import io.opentelemetry.proto.trace.v1.internal.Span;
import io.opentelemetry.proto.trace.v1.internal.Status;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Marshaler} to convert SDK {@link SpanData} to OTLP ExportTraceServiceRequest.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class TraceRequestMarshaler extends MarshalerWithSize implements Marshaler {

  // In practice, there is often only one thread that calls this code in the BatchSpanProcessor so
  // reusing buffers for the thread is almost free. Even with multiple threads, it should still be
  // worth it and is common practice in serialization libraries such as Jackson.
  private static final ThreadLocal<ThreadLocalCache> THREAD_LOCAL_CACHE = new ThreadLocal<>();

  private final ResourceSpansMarshaler[] resourceSpansMarshalers;

  /**
   * Returns a {@link TraceRequestMarshaler} that can be used to convert the provided {@link
   * SpanData} into a serialized OTLP ExportTraceServiceRequest.
   */
  public static TraceRequestMarshaler create(Collection<SpanData> spanDataList) {
    Map<Resource, Map<InstrumentationLibraryInfo, List<SpanMarshaler>>> resourceAndLibraryMap =
        groupByResourceAndLibrary(spanDataList);

    final ResourceSpansMarshaler[] resourceSpansMarshalers =
        new ResourceSpansMarshaler[resourceAndLibraryMap.size()];
    int posResource = 0;
    for (Map.Entry<Resource, Map<InstrumentationLibraryInfo, List<SpanMarshaler>>> entry :
        resourceAndLibraryMap.entrySet()) {
      final InstrumentationLibrarySpansMarshaler[] instrumentationLibrarySpansMarshalers =
          new InstrumentationLibrarySpansMarshaler[entry.getValue().size()];
      int posInstrumentation = 0;
      for (Map.Entry<InstrumentationLibraryInfo, List<SpanMarshaler>> entryIs :
          entry.getValue().entrySet()) {
        instrumentationLibrarySpansMarshalers[posInstrumentation++] =
            new InstrumentationLibrarySpansMarshaler(
                InstrumentationLibraryMarshaler.create(entryIs.getKey()),
                MarshalerUtil.toBytes(entryIs.getKey().getSchemaUrl()),
                entryIs.getValue());
      }
      resourceSpansMarshalers[posResource++] =
          new ResourceSpansMarshaler(
              ResourceMarshaler.create(entry.getKey()),
              MarshalerUtil.toBytes(entry.getKey().getSchemaUrl()),
              instrumentationLibrarySpansMarshalers);
    }

    return new TraceRequestMarshaler(resourceSpansMarshalers);
  }

  private TraceRequestMarshaler(ResourceSpansMarshaler[] resourceSpansMarshalers) {
    super(
        MarshalerUtil.sizeRepeatedMessage(
            ExportTraceServiceRequest.RESOURCE_SPANS_FIELD_NUMBER, resourceSpansMarshalers));
    this.resourceSpansMarshalers = resourceSpansMarshalers;
  }

  @Override
  public void writeTo(CodedOutputStream output) throws IOException {
    MarshalerUtil.marshalRepeatedMessage(
        ExportTraceServiceRequest.RESOURCE_SPANS_FIELD_NUMBER, resourceSpansMarshalers, output);
  }

  private static final class ResourceSpansMarshaler extends MarshalerWithSize {
    private final ResourceMarshaler resourceMarshaler;
    private final byte[] schemaUrl;
    private final InstrumentationLibrarySpansMarshaler[] instrumentationLibrarySpansMarshalers;

    private ResourceSpansMarshaler(
        ResourceMarshaler resourceMarshaler,
        byte[] schemaUrl,
        InstrumentationLibrarySpansMarshaler[] instrumentationLibrarySpansMarshalers) {
      super(calculateSize(resourceMarshaler, schemaUrl, instrumentationLibrarySpansMarshalers));
      this.resourceMarshaler = resourceMarshaler;
      this.schemaUrl = schemaUrl;
      this.instrumentationLibrarySpansMarshalers = instrumentationLibrarySpansMarshalers;
    }

    @Override
    public void writeTo(CodedOutputStream output) throws IOException {
      MarshalerUtil.marshalMessage(ResourceSpans.RESOURCE_FIELD_NUMBER, resourceMarshaler, output);
      MarshalerUtil.marshalRepeatedMessage(
          ResourceSpans.INSTRUMENTATION_LIBRARY_SPANS_FIELD_NUMBER,
          instrumentationLibrarySpansMarshalers,
          output);
      MarshalerUtil.marshalBytes(ResourceSpans.SCHEMA_URL_FIELD_NUMBER, schemaUrl, output);
    }

    private static int calculateSize(
        ResourceMarshaler resourceMarshaler,
        byte[] schemaUrl,
        InstrumentationLibrarySpansMarshaler[] instrumentationLibrarySpansMarshalers) {
      int size = 0;
      size += MarshalerUtil.sizeMessage(ResourceSpans.RESOURCE_FIELD_NUMBER, resourceMarshaler);
      size += MarshalerUtil.sizeBytes(ResourceSpans.SCHEMA_URL_FIELD_NUMBER, schemaUrl);
      size +=
          MarshalerUtil.sizeRepeatedMessage(
              ResourceSpans.INSTRUMENTATION_LIBRARY_SPANS_FIELD_NUMBER,
              instrumentationLibrarySpansMarshalers);
      return size;
    }
  }

  private static final class InstrumentationLibrarySpansMarshaler extends MarshalerWithSize {
    private final InstrumentationLibraryMarshaler instrumentationLibrary;
    private final List<SpanMarshaler> spanMarshalers;
    private final byte[] schemaUrl;

    private InstrumentationLibrarySpansMarshaler(
        InstrumentationLibraryMarshaler instrumentationLibrary,
        byte[] schemaUrl,
        List<SpanMarshaler> spanMarshalers) {
      super(calculateSize(instrumentationLibrary, schemaUrl, spanMarshalers));
      this.instrumentationLibrary = instrumentationLibrary;
      this.schemaUrl = schemaUrl;
      this.spanMarshalers = spanMarshalers;
    }

    @Override
    public void writeTo(CodedOutputStream output) throws IOException {
      MarshalerUtil.marshalMessage(
          InstrumentationLibrarySpans.INSTRUMENTATION_LIBRARY_FIELD_NUMBER,
          instrumentationLibrary,
          output);
      MarshalerUtil.marshalRepeatedMessage(
          InstrumentationLibrarySpans.SPANS_FIELD_NUMBER, spanMarshalers, output);
      MarshalerUtil.marshalBytes(
          InstrumentationLibrarySpans.SCHEMA_URL_FIELD_NUMBER, schemaUrl, output);
    }

    private static int calculateSize(
        InstrumentationLibraryMarshaler instrumentationLibrary,
        byte[] schemaUrl,
        List<SpanMarshaler> spanMarshalers) {
      int size = 0;
      size +=
          MarshalerUtil.sizeMessage(
              InstrumentationLibrarySpans.INSTRUMENTATION_LIBRARY_FIELD_NUMBER,
              instrumentationLibrary);
      size +=
          MarshalerUtil.sizeBytes(InstrumentationLibrarySpans.SCHEMA_URL_FIELD_NUMBER, schemaUrl);
      size +=
          MarshalerUtil.sizeRepeatedMessage(
              InstrumentationLibrarySpans.SPANS_FIELD_NUMBER, spanMarshalers);
      return size;
    }
  }

  private static final class SpanMarshaler extends MarshalerWithSize {
    private final byte[] traceId;
    private final byte[] spanId;
    private final byte[] parentSpanId;
    private final byte[] name;
    private final int spanKind;
    private final long startEpochNanos;
    private final long endEpochNanos;
    private final AttributeMarshaler[] attributeMarshalers;
    private final int droppedAttributesCount;
    private final SpanEventMarshaler[] spanEventMarshalers;
    private final int droppedEventsCount;
    private final SpanLinkMarshaler[] spanLinkMarshalers;
    private final int droppedLinksCount;
    private final SpanStatusMarshaler spanStatusMarshaler;

    // Because SpanMarshaler is always part of a repeated field, it cannot return "null".
    static SpanMarshaler create(SpanData spanData, ThreadLocalCache threadLocalCache) {
      AttributeMarshaler[] attributeMarshalers =
          AttributeMarshaler.createRepeated(spanData.getAttributes());
      SpanEventMarshaler[] spanEventMarshalers = SpanEventMarshaler.create(spanData.getEvents());
      SpanLinkMarshaler[] spanLinkMarshalers =
          SpanLinkMarshaler.create(spanData.getLinks(), threadLocalCache);
      Map<String, byte[]> idBytesCache = threadLocalCache.idBytesCache;

      byte[] traceId =
          idBytesCache.computeIfAbsent(
              spanData.getSpanContext().getTraceId(),
              unused -> spanData.getSpanContext().getTraceIdBytes());
      byte[] spanId =
          idBytesCache.computeIfAbsent(
              spanData.getSpanContext().getSpanId(),
              unused -> spanData.getSpanContext().getSpanIdBytes());

      byte[] parentSpanId = MarshalerUtil.EMPTY_BYTES;
      SpanContext parentSpanContext = spanData.getParentSpanContext();
      if (parentSpanContext.isValid()) {
        parentSpanId =
            idBytesCache.computeIfAbsent(
                spanData.getParentSpanContext().getSpanId(),
                unused -> spanData.getParentSpanContext().getSpanIdBytes());
      }

      return new SpanMarshaler(
          traceId,
          spanId,
          parentSpanId,
          MarshalerUtil.toBytes(spanData.getName()),
          toProtoSpanKind(spanData.getKind()),
          spanData.getStartEpochNanos(),
          spanData.getEndEpochNanos(),
          attributeMarshalers,
          spanData.getTotalAttributeCount() - spanData.getAttributes().size(),
          spanEventMarshalers,
          spanData.getTotalRecordedEvents() - spanData.getEvents().size(),
          spanLinkMarshalers,
          spanData.getTotalRecordedLinks() - spanData.getLinks().size(),
          SpanStatusMarshaler.create(spanData.getStatus()));
    }

    private SpanMarshaler(
        byte[] traceId,
        byte[] spanId,
        byte[] parentSpanId,
        byte[] name,
        int spanKind,
        long startEpochNanos,
        long endEpochNanos,
        AttributeMarshaler[] attributeMarshalers,
        int droppedAttributesCount,
        SpanEventMarshaler[] spanEventMarshalers,
        int droppedEventsCount,
        SpanLinkMarshaler[] spanLinkMarshalers,
        int droppedLinksCount,
        SpanStatusMarshaler spanStatusMarshaler) {
      super(
          calculateSize(
              traceId,
              spanId,
              parentSpanId,
              name,
              spanKind,
              startEpochNanos,
              endEpochNanos,
              attributeMarshalers,
              droppedAttributesCount,
              spanEventMarshalers,
              droppedEventsCount,
              spanLinkMarshalers,
              droppedLinksCount,
              spanStatusMarshaler));
      this.traceId = traceId;
      this.spanId = spanId;
      this.parentSpanId = parentSpanId;
      this.name = name;
      this.spanKind = spanKind;
      this.startEpochNanos = startEpochNanos;
      this.endEpochNanos = endEpochNanos;
      this.attributeMarshalers = attributeMarshalers;
      this.droppedAttributesCount = droppedAttributesCount;
      this.spanEventMarshalers = spanEventMarshalers;
      this.droppedEventsCount = droppedEventsCount;
      this.spanLinkMarshalers = spanLinkMarshalers;
      this.droppedLinksCount = droppedLinksCount;
      this.spanStatusMarshaler = spanStatusMarshaler;
    }

    @Override
    public void writeTo(CodedOutputStream output) throws IOException {
      MarshalerUtil.marshalBytes(Span.TRACE_ID_FIELD_NUMBER, traceId, output);
      MarshalerUtil.marshalBytes(Span.SPAN_ID_FIELD_NUMBER, spanId, output);
      // TODO: Set TraceState;
      MarshalerUtil.marshalBytes(Span.PARENT_SPAN_ID_FIELD_NUMBER, parentSpanId, output);
      MarshalerUtil.marshalBytes(Span.NAME_FIELD_NUMBER, name, output);

      MarshalerUtil.marshalEnum(Span.KIND_FIELD_NUMBER, spanKind, output);

      MarshalerUtil.marshalFixed64(Span.START_TIME_UNIX_NANO_FIELD_NUMBER, startEpochNanos, output);
      MarshalerUtil.marshalFixed64(Span.END_TIME_UNIX_NANO_FIELD_NUMBER, endEpochNanos, output);

      MarshalerUtil.marshalRepeatedMessage(
          Span.ATTRIBUTES_FIELD_NUMBER, attributeMarshalers, output);
      MarshalerUtil.marshalUInt32(
          Span.DROPPED_ATTRIBUTES_COUNT_FIELD_NUMBER, droppedAttributesCount, output);

      MarshalerUtil.marshalRepeatedMessage(Span.EVENTS_FIELD_NUMBER, spanEventMarshalers, output);
      MarshalerUtil.marshalUInt32(
          Span.DROPPED_EVENTS_COUNT_FIELD_NUMBER, droppedEventsCount, output);

      MarshalerUtil.marshalRepeatedMessage(Span.LINKS_FIELD_NUMBER, spanLinkMarshalers, output);
      MarshalerUtil.marshalUInt32(Span.DROPPED_LINKS_COUNT_FIELD_NUMBER, droppedLinksCount, output);

      MarshalerUtil.marshalMessage(Span.STATUS_FIELD_NUMBER, spanStatusMarshaler, output);
    }

    private static int calculateSize(
        byte[] traceId,
        byte[] spanId,
        byte[] parentSpanId,
        byte[] name,
        int spanKind,
        long startEpochNanos,
        long endEpochNanos,
        AttributeMarshaler[] attributeMarshalers,
        int droppedAttributesCount,
        SpanEventMarshaler[] spanEventMarshalers,
        int droppedEventsCount,
        SpanLinkMarshaler[] spanLinkMarshalers,
        int droppedLinksCount,
        SpanStatusMarshaler spanStatusMarshaler) {
      int size = 0;
      size += MarshalerUtil.sizeBytes(Span.TRACE_ID_FIELD_NUMBER, traceId);
      size += MarshalerUtil.sizeBytes(Span.SPAN_ID_FIELD_NUMBER, spanId);
      // TODO: Set TraceState;
      size += MarshalerUtil.sizeBytes(Span.PARENT_SPAN_ID_FIELD_NUMBER, parentSpanId);
      size += MarshalerUtil.sizeBytes(Span.NAME_FIELD_NUMBER, name);

      size += MarshalerUtil.sizeEnum(Span.KIND_FIELD_NUMBER, spanKind);

      size += MarshalerUtil.sizeFixed64(Span.START_TIME_UNIX_NANO_FIELD_NUMBER, startEpochNanos);
      size += MarshalerUtil.sizeFixed64(Span.END_TIME_UNIX_NANO_FIELD_NUMBER, endEpochNanos);

      size += MarshalerUtil.sizeRepeatedMessage(Span.ATTRIBUTES_FIELD_NUMBER, attributeMarshalers);
      size +=
          MarshalerUtil.sizeUInt32(
              Span.DROPPED_ATTRIBUTES_COUNT_FIELD_NUMBER, droppedAttributesCount);

      size += MarshalerUtil.sizeRepeatedMessage(Span.EVENTS_FIELD_NUMBER, spanEventMarshalers);
      size += MarshalerUtil.sizeUInt32(Span.DROPPED_EVENTS_COUNT_FIELD_NUMBER, droppedEventsCount);

      size += MarshalerUtil.sizeRepeatedMessage(Span.LINKS_FIELD_NUMBER, spanLinkMarshalers);
      size += MarshalerUtil.sizeUInt32(Span.DROPPED_LINKS_COUNT_FIELD_NUMBER, droppedLinksCount);

      size += MarshalerUtil.sizeMessage(Span.STATUS_FIELD_NUMBER, spanStatusMarshaler);
      return size;
    }
  }

  private static final class SpanEventMarshaler extends MarshalerWithSize {
    private static final SpanEventMarshaler[] EMPTY = new SpanEventMarshaler[0];
    private final long epochNanos;
    private final byte[] name;
    private final AttributeMarshaler[] attributeMarshalers;
    private final int droppedAttributesCount;

    static SpanEventMarshaler[] create(List<EventData> events) {
      if (events.isEmpty()) {
        return EMPTY;
      }

      SpanEventMarshaler[] result = new SpanEventMarshaler[events.size()];
      int pos = 0;
      for (EventData event : events) {
        result[pos++] =
            new SpanEventMarshaler(
                event.getEpochNanos(),
                MarshalerUtil.toBytes(event.getName()),
                AttributeMarshaler.createRepeated(event.getAttributes()),
                event.getTotalAttributeCount() - event.getAttributes().size());
      }

      return result;
    }

    private SpanEventMarshaler(
        long epochNanos,
        byte[] name,
        AttributeMarshaler[] attributeMarshalers,
        int droppedAttributesCount) {
      super(calculateSize(epochNanos, name, attributeMarshalers, droppedAttributesCount));
      this.epochNanos = epochNanos;
      this.name = name;
      this.attributeMarshalers = attributeMarshalers;
      this.droppedAttributesCount = droppedAttributesCount;
    }

    @Override
    public void writeTo(CodedOutputStream output) throws IOException {
      MarshalerUtil.marshalFixed64(Span.Event.TIME_UNIX_NANO_FIELD_NUMBER, epochNanos, output);
      MarshalerUtil.marshalBytes(Span.Event.NAME_FIELD_NUMBER, name, output);
      MarshalerUtil.marshalRepeatedMessage(
          Span.Event.ATTRIBUTES_FIELD_NUMBER, attributeMarshalers, output);
      MarshalerUtil.marshalUInt32(
          Span.Event.DROPPED_ATTRIBUTES_COUNT_FIELD_NUMBER, droppedAttributesCount, output);
    }

    private static int calculateSize(
        long epochNanos,
        byte[] name,
        AttributeMarshaler[] attributeMarshalers,
        int droppedAttributesCount) {
      int size = 0;
      size += MarshalerUtil.sizeFixed64(Span.Event.TIME_UNIX_NANO_FIELD_NUMBER, epochNanos);
      size += MarshalerUtil.sizeBytes(Span.Event.NAME_FIELD_NUMBER, name);
      size +=
          MarshalerUtil.sizeRepeatedMessage(
              Span.Event.ATTRIBUTES_FIELD_NUMBER, attributeMarshalers);
      size +=
          MarshalerUtil.sizeUInt32(
              Span.Event.DROPPED_ATTRIBUTES_COUNT_FIELD_NUMBER, droppedAttributesCount);
      return size;
    }
  }

  private static final class SpanLinkMarshaler extends MarshalerWithSize {
    private static final SpanLinkMarshaler[] EMPTY = new SpanLinkMarshaler[0];
    private final byte[] traceId;
    private final byte[] spanId;
    private final AttributeMarshaler[] attributeMarshalers;
    private final int droppedAttributesCount;

    static SpanLinkMarshaler[] create(List<LinkData> links, ThreadLocalCache threadLocalCache) {
      if (links.isEmpty()) {
        return EMPTY;
      }
      Map<String, byte[]> idBytesCache = threadLocalCache.idBytesCache;

      SpanLinkMarshaler[] result = new SpanLinkMarshaler[links.size()];
      int pos = 0;
      for (LinkData link : links) {
        result[pos++] =
            new SpanLinkMarshaler(
                idBytesCache.computeIfAbsent(
                    link.getSpanContext().getTraceId(),
                    unused -> link.getSpanContext().getTraceIdBytes()),
                idBytesCache.computeIfAbsent(
                    link.getSpanContext().getSpanId(),
                    unused -> link.getSpanContext().getSpanIdBytes()),
                AttributeMarshaler.createRepeated(link.getAttributes()),
                link.getTotalAttributeCount() - link.getAttributes().size());
      }

      return result;
    }

    private SpanLinkMarshaler(
        byte[] traceId,
        byte[] spanId,
        AttributeMarshaler[] attributeMarshalers,
        int droppedAttributesCount) {
      super(calculateSize(traceId, spanId, attributeMarshalers, droppedAttributesCount));
      this.traceId = traceId;
      this.spanId = spanId;
      this.attributeMarshalers = attributeMarshalers;
      this.droppedAttributesCount = droppedAttributesCount;
    }

    @Override
    public void writeTo(CodedOutputStream output) throws IOException {
      MarshalerUtil.marshalBytes(Span.Link.TRACE_ID_FIELD_NUMBER, traceId, output);
      MarshalerUtil.marshalBytes(Span.Link.SPAN_ID_FIELD_NUMBER, spanId, output);
      // TODO: Set TraceState;
      MarshalerUtil.marshalRepeatedMessage(
          Span.Link.ATTRIBUTES_FIELD_NUMBER, attributeMarshalers, output);
      MarshalerUtil.marshalUInt32(
          Span.Link.DROPPED_ATTRIBUTES_COUNT_FIELD_NUMBER, droppedAttributesCount, output);
    }

    private static int calculateSize(
        byte[] traceId,
        byte[] spanId,
        AttributeMarshaler[] attributeMarshalers,
        int droppedAttributesCount) {
      int size = 0;
      size += MarshalerUtil.sizeBytes(Span.Link.TRACE_ID_FIELD_NUMBER, traceId);
      size += MarshalerUtil.sizeBytes(Span.Link.SPAN_ID_FIELD_NUMBER, spanId);
      // TODO: Set TraceState;
      size +=
          MarshalerUtil.sizeRepeatedMessage(Span.Link.ATTRIBUTES_FIELD_NUMBER, attributeMarshalers);
      size +=
          MarshalerUtil.sizeUInt32(
              Span.Link.DROPPED_ATTRIBUTES_COUNT_FIELD_NUMBER, droppedAttributesCount);
      return size;
    }
  }

  private static final class SpanStatusMarshaler extends MarshalerWithSize {
    private final int protoStatusCode;
    private final int deprecatedStatusCode;
    private final byte[] description;

    static SpanStatusMarshaler create(StatusData status) {
      int protoStatusCode = Status.StatusCode.STATUS_CODE_UNSET_VALUE;
      int deprecatedStatusCode = Status.DeprecatedStatusCode.DEPRECATED_STATUS_CODE_OK_VALUE;
      if (status.getStatusCode() == StatusCode.OK) {
        protoStatusCode = Status.StatusCode.STATUS_CODE_OK_VALUE;
      } else if (status.getStatusCode() == StatusCode.ERROR) {
        protoStatusCode = Status.StatusCode.STATUS_CODE_ERROR_VALUE;
        deprecatedStatusCode =
            Status.DeprecatedStatusCode.DEPRECATED_STATUS_CODE_UNKNOWN_ERROR_VALUE;
      }
      byte[] description = MarshalerUtil.toBytes(status.getDescription());
      return new SpanStatusMarshaler(protoStatusCode, deprecatedStatusCode, description);
    }

    private SpanStatusMarshaler(int protoStatusCode, int deprecatedStatusCode, byte[] description) {
      super(computeSize(protoStatusCode, deprecatedStatusCode, description));
      this.protoStatusCode = protoStatusCode;
      this.deprecatedStatusCode = deprecatedStatusCode;
      this.description = description;
    }

    @Override
    public void writeTo(CodedOutputStream output) throws IOException {
      if (deprecatedStatusCode != Status.DeprecatedStatusCode.DEPRECATED_STATUS_CODE_OK_VALUE) {
        MarshalerUtil.marshalEnum(
            Status.DEPRECATED_CODE_FIELD_NUMBER, deprecatedStatusCode, output);
      }
      MarshalerUtil.marshalBytes(Status.MESSAGE_FIELD_NUMBER, description, output);
      if (protoStatusCode != Status.StatusCode.STATUS_CODE_UNSET_VALUE) {
        MarshalerUtil.marshalEnum(Status.CODE_FIELD_NUMBER, protoStatusCode, output);
      }
    }

    private static int computeSize(
        int protoStatusCode, int deprecatedStatusCode, byte[] description) {
      int size = 0;
      size += MarshalerUtil.sizeEnum(Status.DEPRECATED_CODE_FIELD_NUMBER, deprecatedStatusCode);
      size += MarshalerUtil.sizeBytes(Status.MESSAGE_FIELD_NUMBER, description);
      size += MarshalerUtil.sizeEnum(Status.CODE_FIELD_NUMBER, protoStatusCode);
      return size;
    }
  }

  private static Map<Resource, Map<InstrumentationLibraryInfo, List<SpanMarshaler>>>
      groupByResourceAndLibrary(Collection<SpanData> spanDataList) {
    ThreadLocalCache threadLocalCache = getThreadLocalCache();
    try {
      return MarshalerUtil.groupByResourceAndLibrary(
          spanDataList,
          // TODO(anuraaga): Replace with an internal SdkData type of interface that exposes these
          // two.
          SpanData::getResource,
          SpanData::getInstrumentationLibraryInfo,
          data -> SpanMarshaler.create(data, threadLocalCache));
    } finally {
      threadLocalCache.idBytesCache.clear();
    }
  }

  private static int toProtoSpanKind(SpanKind kind) {
    switch (kind) {
      case INTERNAL:
        return Span.SpanKind.SPAN_KIND_INTERNAL_VALUE;
      case SERVER:
        return Span.SpanKind.SPAN_KIND_SERVER_VALUE;
      case CLIENT:
        return Span.SpanKind.SPAN_KIND_CLIENT_VALUE;
      case PRODUCER:
        return Span.SpanKind.SPAN_KIND_PRODUCER_VALUE;
      case CONSUMER:
        return Span.SpanKind.SPAN_KIND_CONSUMER_VALUE;
    }
    return -1;
  }

  private static ThreadLocalCache getThreadLocalCache() {
    ThreadLocalCache result = THREAD_LOCAL_CACHE.get();
    if (result == null) {
      result = new ThreadLocalCache();
      THREAD_LOCAL_CACHE.set(result);
    }
    return result;
  }

  private static final class ThreadLocalCache {
    final Map<String, byte[]> idBytesCache = new HashMap<>();
  }
}
