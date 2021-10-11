package kensu.ingestion;

import io.kensu.collector.config.KensuTracerFactory;
import zipkin2.Span;

import java.util.List;

public class KensuIngester {
  void ingestTraces(List<List<Span>> traces) {
    KensuTracerFactory.prepareTracer(); // FIXME!

    for (List<Span> trace: traces){
      ingestTrace(trace);
    }
  }

  void ingestTrace(List<Span> traces) {

  }
}
