package kensu.ingestion;

import io.kensu.collector.config.KensuTracerFactory;
import zipkin2.Span;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class KensuIngester {
  public static void ingestTraces(List<List<Span>> traces) {
    KensuTracerFactory.prepareTracer(); // FIXME! find better place to run this

    for (List<Span> trace: traces){
      ingestTrace(trace);
    }
  }

  static void ingestTrace(List<Span> spans) {
    // root span
    Span rootSpan = null;
    Set<Span> childSpans = new HashSet<>();
    for (Span span: spans){
      if (span.traceId().equals(span.id())){
        rootSpan = span;
      } else {
        childSpans.add(span);
      }
    }
    // children spans
    if (rootSpan != null) {
      // FIXME: println
      System.out.println("Ingesting trace rootId: " + rootSpan.id() + " rootParent: " + rootSpan.parentId() + " numChildren: " + childSpans.size());
      // FIXME: here we likely want timestamp of finishing instead!?
      // Epoch microseconds of the start of this span, possibly absent if this an incomplete span.
      Long tsMicroSecs = rootSpan.timestampAsLong();
      Instant ts = Instant.ofEpochMilli(tsMicroSecs / 1000);
      new JaegerIngester().finish(ts, rootSpan, childSpans);
    } else {
      // FIXME: println
      System.out.println("WARNING! No root span found for a trace of:" + spans.toString()) ;
    }
  }
}
