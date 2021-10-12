package kensu.ingestion;

//import com.google.common.cache.Cache;
//import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.kensu.collector.config.DamProcessEnvironment;
import io.kensu.collector.model.DamBatchBuilder;
import io.kensu.collector.model.DamDataCatalogEntry;
import io.kensu.collector.model.DamSchemaUtils;
import io.kensu.collector.model.datasource.HttpDatasourceNameFormatter;
import io.kensu.collector.model.datasource.JdbcDatasourceNameFormatter;
import io.kensu.dam.util.DataSources;
import io.kensu.dam.util.Lineages;
import io.kensu.dam.ApiClient;
import io.kensu.dam.ApiException;
import io.kensu.dam.ManageKensuDamEntitiesApi;
import io.kensu.dam.OfflineFileApiClient;
import io.kensu.dam.model.*;
import io.kensu.dam.model.Process;
import io.kensu.collector.utils.jdbc.parser.DamJdbcQueryParser;
import io.kensu.collector.utils.jdbc.parser.ReferencedSchemaFieldsInfo;
//import io.opentracing.contrib.reporter.Reporter;
//import io.opentracing.contrib.reporter.Span;
//import io.opentracing.tag.Tag;
//import io.opentracing.tag.Tags;
import net.sf.jsqlparser.JSQLParserException;
import org.springframework.boot.json.GsonJsonParser;
import zipkin2.Span;
//import zipkin2.Span;
//import zipkin2.reporter.AsyncReporter;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

// FIXME: use scala client instead?

// FIXME: this is from Jaeger!
final class Tags {
  public static final String SPAN_KIND_SERVER = "server";
  public static final String SPAN_KIND_CLIENT = "client";
  public static final String SPAN_KIND_PRODUCER = "producer";
  public static final String SPAN_KIND_CONSUMER = "consumer";
  public static final String HTTP_URL = ("http.url");
  public static final String HTTP_STATUS = ("http.status_code");
  public static final String HTTP_METHOD = ("http.method");
//  public static final StringTag PEER_SERVICE = new StringTag("peer.service");
//  public static final StringTag PEER_HOSTNAME = new StringTag("peer.hostname");
//  public static final IntTag PEER_PORT = new IntTag("peer.port");
//  public static final IntTag SAMPLING_PRIORITY = new IntTag("sampling.priority");
  public static final String SPAN_KIND = ("span.kind");
//  public static final StringTag COMPONENT = new StringTag("component");
//  public static final BooleanTag ERROR = new BooleanTag("error");
  public static final String DB_TYPE = ("db.type");
  public static final String DB_INSTANCE = ("db.instance");
  public static final String DB_USER = ("db.user");
  public static final String DB_STATEMENT = ("db.statement");
  //public static final Steing MESSAGE_BUS_DESTINATION = new StringTag("message_bus.destination");

  private Tags() {
  }
}


public class JaegerIngester {
  static final Logger logger = Logger.getLogger(JaegerIngester.class.getName());

  //public TracerReporter(Logger logger, AbstractUrlsTransformer springUrlsTransformer) {
  public JaegerIngester() {
  }

  protected DamProcessEnvironment damEnv = new DamProcessEnvironment();


  protected String getTagOrDefault(String tagKey, Span span, String defaultValue) {
    return span.tags().getOrDefault(tagKey, defaultValue);
  }

  protected Integer getIntegerTagOrDefault(String tagKey, Span span, Integer defaultValue) {
    String res = span.tags().get(tagKey);
    if (res != null){
      return Integer.parseInt(res);
    } else {
      return defaultValue;
    }
  }

  protected Double getDoubleTagOrDefault(String tagKey, Span span, Double defaultValue) {
    String res = span.tags().get(tagKey);
    if (res != null){
      return Double.parseDouble(res);
    } else {
      return defaultValue;
    }
  }

  protected <T> T getTagsOfComplexType(String tagKey, Span span, Type cls, T defaultValue) {
    String tagsString = span.tags().getOrDefault(tagKey, null);
    if (tagsString != null) {
      Gson gson = new Gson();
      return (T)gson.fromJson(tagsString, cls);
    } else {
      return defaultValue;
    }
  }

  protected Set<String> getTagsOfSetString(Span span, String tagKey) {
    Type t = new TypeToken<Set<String>>() {}.getType();
    return getTagsOfComplexType(tagKey, span, t, new HashSet<String>());
  }


  protected Set<FieldDef> getTagsOfSetFieldDef(Span span, String tagKey) {
    Type t = new TypeToken<Set<FieldDef>>() {}.getType();
    return getTagsOfComplexType(tagKey, span, t, new HashSet<FieldDef>());
  }

    protected String getParentId(Span span){
    String maybeParentId = span.parentId();
    if ((maybeParentId != null) && !maybeParentId.equals(span.id())){
      return maybeParentId;
    }
    return null;
  }

  public void finish(Instant timestamp, Span span, Set<Span> children) {
    try {
      String maybeParentId = getParentId(span);
      if (maybeParentId != null) {
        //addToCache(maybeParentId, span);
      } else {
        // //   - http.status_code: 200
        // //   - component: java-web-servlet
        // //   - span.kind: server
        // //   - http.url: http://localhost/people/search/findByLastName
        // //   - http.method: GET
        // //  http.request.url.path.pattern	    /v1/visit/{visitId}?outputFormat={outputFormat}
        // //  http.request.url.path.parameters	    visitId
        // //  http.request.url.query.parameters	outputFormat
        // //   - DamOutputSchema: [class FieldDef {
        Integer httpStatus = getIntegerTagOrDefault(Tags.HTTP_STATUS, span, 0);
        String httpUrl = getTagOrDefault(Tags.HTTP_URL, span, null);
        String httpMethod = getTagOrDefault(Tags.HTTP_METHOD, span, null);
        String httpPathPattern = getTagOrDefault("http.request.url.path.pattern", span, null);
        // FIXME?: how!?
        Set<String> httpPathParameters = getTagsOfSetString(span, "http.request.url.path.parameters");
        Set<String> httpQueryParameters = getTagsOfSetString(span, "http.request.url.query.parameters");

        if (httpMethod == null) {
          // this is probably not a span that we should consider for lineage purpose
          return;
        }

        Set<Span> toBeRemoved = new HashSet<>();
        toBeRemoved.add(span);

        // this is the main SPAN, report all the stuff which was gathered so far
        DamBatchBuilder batchBuilder = new DamBatchBuilder().withDefaultLocation();
        PhysicalLocationRef defaultLocationRef = DamBatchBuilder.DEFAULT_LOCATION_REF;

        String logMessage = createLogMessage(timestamp, "Finish", span);
        logger.fine(logMessage);


        Set<FieldDef> endpointQueryFields = new HashSet<>();
        // adding pathParams & queryParams as fields
        if (httpPathParameters != null && !httpPathParameters.isEmpty()) {
          httpPathParameters.stream().map(DamSchemaUtils::fieldWithMissingInfo).forEach(endpointQueryFields::add);
        }
        if (httpQueryParameters != null && !httpQueryParameters.isEmpty()) {
          httpQueryParameters.stream().map(DamSchemaUtils::fieldWithMissingInfo).forEach(endpointQueryFields::add);
        }
        // TODO support content body (like query JSON for example)
        DamDataCatalogEntry endpointQueryCatalogEntry = batchBuilder.addCatalogEntry("HTTP Request",
          endpointQueryFields, httpPathPattern, httpMethod+":"+"http", defaultLocationRef, HttpDatasourceNameFormatter.INST);
        DamDataCatalogEntry endpointQueryCatalogEntryWithResultSchema = null;

        if ((httpStatus >= 200) && (httpStatus < 300)) {
          Map<String, DamDataCatalogEntry> queriedTableCatalogEntries = new HashMap<>();
          if (children == null) children = new HashSet<>();
          Map<String, Map<String, Double>> statsByTable = new HashMap<>();
          Map<String, Map<String, Double>> responseStats = null;

          // children might be => "Query" then "serialize"
          for (Span spanChild : children) {
            toBeRemoved.add(spanChild);
              // Span name in lowercase, rpc method for example.
            if (spanChild.name().equals("Query")) {
                            /*
                                component	-> java-jdbc
                                db.instance	-> classicmodels
                                db.statement -> select productlin0_.productLine as productL1_0_, productlin0_.htmlDescription as htmlDesc2_0_, productlin0_.image as image3_0_, productlin0_.textDescription as textDesc4_0_ from productlines productlin0_ where productlin0_.productLine=?
                                db.type	-> clickhouse
                                internal.span.format -> jaeger
                                peer.address -> localhost:8123
                                peer.service -> classicmodels[clickhouse(localhost:8123)]
                                span.kind -> client
                            */
              String dbInstance = getTagOrDefault(Tags.DB_INSTANCE, spanChild, "");
              String dbType = getTagOrDefault(Tags.DB_TYPE, spanChild, "");
              String dbConnection = getTagOrDefault("peer.address", spanChild, "");
              String dbStatement = getTagOrDefault(Tags.DB_STATEMENT, spanChild, "");
              // SQL reads
              DamJdbcQueryParser damJdbcQueryParser = null;
              ReferencedSchemaFieldsInfo fieldsByTable = null;
              try {
                damJdbcQueryParser = new DamJdbcQueryParser(dbInstance, dbType, dbStatement);
                fieldsByTable = damJdbcQueryParser.guessReferencedInputTableSchemas();
                for (Entry<String, HashSet<FieldDef>> sfd : fieldsByTable.schema.entrySet()) {
                  String schemaAndTableName = sfd.getKey();
                  String dsNameComposed = String.format("%s/%s", dbConnection, schemaAndTableName);
                  HashSet<FieldDef> fields = sfd.getValue();
                  DamDataCatalogEntry dataCatalogEntry = batchBuilder.addCatalogEntry("SQL Query",
                    fields, dsNameComposed, dbType, defaultLocationRef, JdbcDatasourceNameFormatter.INST);
                  queriedTableCatalogEntries.put(schemaAndTableName, dataCatalogEntry);
                }
              } catch (JSQLParserException e) {
                logger.log(Level.SEVERE, String.format("unable to parse (dbInstance: %s, dbType: %s, dbStatement: %s) ", dbInstance, dbType, dbStatement), e);
                if (!dbStatement.startsWith("call next value")) {
                  e.printStackTrace();
                }
              }

              if (fieldsByTable == null) {
                continue;
              }

              // FIXME!!!
              Set<Span> queryChildren = children.stream()
                .filter(x -> { return x.parentId().equals(spanChild.id()); })
                .collect(Collectors.toSet());
              for (Span queryStatsSpan : queryChildren) {
                toBeRemoved.add(queryStatsSpan);
                if (queryStatsSpan.name().equals("QueryResultStats")) {
                                    /*
                                        db.column.1.md -> [,products,productL9_1_0_,String,false]
                                        db.column.1.stats-> {(count,12.0)}
                                        db.column.2.md	-> [,products,productC1_1_0_,String,false]
                                        db.column.2.stats-> {(count,12.0)}
                                        db.column.3.md	-> [,products,productC1_1_1_,String,false]
                                        db.column.3.stats-> {(count,12.0)}
                                        db.count	-> 13
                                        internal.span.format -> jaeger
                                    */
                  // parsed SQL (used to build schema fields) can give access to original name of column if label are in the .md tags
                  //    => to name stats appropriately (using names like in the schema fields)
                  Function<Integer, String> mkKey = (i) -> { return "db.column."+i+".md";};
                  Function<Integer, String> mkStats = (i) -> { return "db.column."+i+".stats";};
                  int keyIndex = 1;
                  String mdKey = mkKey.apply(keyIndex);
                  Optional<Number> dbCount = Optional.ofNullable(getDoubleTagOrDefault("db.count", queryStatsSpan, null));
                  while (queryStatsSpan.tags().containsKey(mdKey)) {
                    Map<String, String> md = getTagsOfComplexType(mdKey, queryStatsSpan,
                      new TypeToken<HashMap<String, String>>() {}.getType(),
                      new HashMap<>());
                    //Map<String, String> md = (Map<String, String>)queryStatsSpan.tags().get(mdKey);
                    String db = md.get("schemaName");
                    db = db.length()==0?dbInstance:db;
                    String table = md.get("tableName");
                    Map<String, Double> stats;
                    if (!statsByTable.containsKey(db+"."+table)) {
                      Map<String, Double> m = new HashMap<>();
                      dbCount.ifPresent(number -> m.put("row.count", number.doubleValue()));
                      statsByTable.put(db+"."+table, m);
                    }
                    stats = statsByTable.get(db+"."+table);
                    String columnOrAlias = md.get("name");
                    HashSet<Entry<FieldDef, String>> columns = fieldsByTable.data.get(db+"."+table);
                    if (columns == null) columns = new HashSet<>();
                    // checking if columnOrAlias is alias (values in data) => if not found the  we consider it as a column
                    String column = columnOrAlias;
                    for (Entry<FieldDef, String> c: columns) {
                      if (c.getValue().equals(columnOrAlias)) {
                        column = c.getKey().getName();
                        break;
                      }
                    }
                    final String columnFinal = column;
                    Map<String, Double> statsTagValue = getTagsOfComplexType(mkStats.apply(keyIndex), queryStatsSpan,
                      new TypeToken<HashMap<String, Double>>() {}.getType(),
                      new HashMap<>());
                    statsTagValue.forEach((k,v) -> stats.put(columnFinal+"."+k, v));
                    mdKey = mkKey.apply(++keyIndex);
                  }
                }
              }
              logger.fine("statsByTable: " + statsByTable);
            } else if (spanChild.name().equals("serialize")) {
              logger.info(spanChild.toString());
                            /*
                            entity.type	-> com.hotjoe.services.ProductLineService$ProductLineView
                            internal.span.format -> jaeger
                            media.type	-> application/json
                            response.schema -> Set of FieldDef
                            */

              // FIXME!
              Set<FieldDef> fieldsSet = getTagsOfSetFieldDef(spanChild, "response.schema");
              responseStats = getTagsOfComplexType("response.stats", spanChild,
                 new TypeToken<HashMap<String, Map<String, Double>>>() {}.getType(),
                 new HashMap<String, Map<String, Double>>());
              endpointQueryCatalogEntryWithResultSchema = batchBuilder.addCatalogEntry("HTTP Request",
                fieldsSet, httpPathPattern, "http", defaultLocationRef, HttpDatasourceNameFormatter.INST);
            }
          }

          final BiFunction<LineageRun, Map<String, Map<String, Double>>, Void> addStatsToLineageRun =
            (lineageRun, statsMap) -> {
              // TODO / FIXME
              // the same table might be present if different queries, with multiple stats (as results)
              //   we will keep only the last one thus, which can be a big issue... WHAT shall we do?
              //     => create several lineages per Query for example may do some goods ??!!
              statsMap.forEach((schemaAndTable, values) -> {
                Map<String, BigDecimal> stats = new HashMap<>();
                // bloody java compile for which Map<String, Object> and Map<String, Double> are incompatible
                values.forEach((k, v) -> { stats.put(k, new BigDecimal(v)); });
                DamDataCatalogEntry entry = queriedTableCatalogEntries.get(schemaAndTable);
                if (entry != null) {
                  DataStatsPK statsPK = new DataStatsPK()
                    .schemaRef(new SchemaRef().byPK(entry.damSchema.getPk()))
                    .lineageRunRef(new LineageRunRef().byPK(lineageRun.getPk()));
                  DataStats dataStats = new DataStats().pk(statsPK).stats(stats);
                  batchBuilder.getBatch()
                    .addDataStatsItem(new BatchDataStats().timestamp(Lineages.NOW.apply())
                      .entity(dataStats));
                } else {
                  logger.warning("We can't connect the built stats to its catalog entry, for table: " + schemaAndTable);
                }
              });
              return null;
            };

          Process process = damEnv.enqueProcess(batchBuilder);

          ProcessRun processRun = damEnv.enqueProcessRun(process, "http:"+httpMethod, batchBuilder);

          // endpointQueryCatalogEntry -> queriedTableCatalogEntries
          Lineages.LineageDef lineageDefIn = null;
          if (!endpointQueryCatalogEntry.fields.isEmpty()) {
            List<DamDataCatalogEntry> nonEmptyOutputs = queriedTableCatalogEntries.values().stream().filter(q -> !q.fields.isEmpty()).collect(Collectors.toList());
            if (!nonEmptyOutputs.isEmpty()) {
              // no lineages for schema without fields, if no more schema in or out after, then... not lineage
              ArrayList<DamDataCatalogEntry> inputs = new ArrayList<>();
              inputs.add(endpointQueryCatalogEntry);

              lineageDefIn = new Lineages.LineageDef(
                process,
                "query",
                inputs,
                new ArrayList<>(queriedTableCatalogEntries.values()),
                Lineages.DIRECT, // FIXME !!!! this is not sufficient at all
                "APPEND",
                Lineages.NOW
              );
              batchBuilder.addFromOtherBatch(lineageDefIn.damBatch);
              // add process & lineage runs!!!
              BatchEntityReport lineageRunInBatch = lineageDefIn.runIn(new ProcessRunRef().byPK(processRun.getPk()), Lineages.NOW.apply(), false);
              batchBuilder.addFromOtherBatch(lineageRunInBatch);
              BatchLineageRun lineageRunIn = lineageRunInBatch.getLineageRuns().get(0);
              // TODO add endpoint stats!!! to lineageRunIn if any found...
              addStatsToLineageRun.apply(lineageRunIn.getEntity(), statsByTable);
            }
          }

          // queriedTableCatalogEntries -> endpointQueryCatalogEntry
          Lineages.LineageDef lineageDefOut = null;
          if (!endpointQueryCatalogEntryWithResultSchema.fields.isEmpty()) {
            List<DamDataCatalogEntry> nonEmptyInputs = queriedTableCatalogEntries.values().stream().filter(q -> !q.fields.isEmpty()).collect(Collectors.toList());
            if (!nonEmptyInputs.isEmpty()) {
              // no lineages for schema without fields, if no more schema in or out after, then... not lineage
              ArrayList<DamDataCatalogEntry> outputs = new ArrayList<>();
              outputs.add(endpointQueryCatalogEntryWithResultSchema);

              lineageDefOut = new Lineages.LineageDef(
                process,
                "query",
                new ArrayList<>(queriedTableCatalogEntries.values()),
                outputs,
                OUT_ENDS_WITH_IN, // FIXME !!!! this is not sufficient at all -> at least we use "finish by"
                "APPEND",
                Lineages.NOW
              );
              batchBuilder.addFromOtherBatch(lineageDefOut.damBatch);
              // add process & lineage runs!!!
              BatchEntityReport lineageRunOutBatch = lineageDefOut.runIn(new ProcessRunRef().byPK(processRun.getPk()), Lineages.NOW.apply(), false);
              batchBuilder.addFromOtherBatch(lineageRunOutBatch);
              BatchLineageRun lineageRunOut = lineageRunOutBatch.getLineageRuns().get(0);
              addStatsToLineageRun.apply(lineageRunOut.getEntity(), statsByTable);

              DataStatsPK responseDataStatsPK = new DataStatsPK()
                .schemaRef(new SchemaRef().byPK(endpointQueryCatalogEntryWithResultSchema.damSchema.getPk()))
                .lineageRunRef(new LineageRunRef().byPK(lineageRunOut.getEntity().getPk()));
              final Map<String, BigDecimal> responseDataStatsMap = new HashMap<>();
              responseStats.forEach((k, map) -> map.forEach((subK, d) -> responseDataStatsMap.put(k+"."+subK, new BigDecimal(d))));
              DataStats responseDataStats = new DataStats().pk(responseDataStatsPK).stats(responseDataStatsMap);
              batchBuilder.getBatch()
                .addDataStatsItem(new BatchDataStats().timestamp(Lineages.NOW.apply())
                  .entity(responseDataStats));
            }
          }
        }
        // FIXME: remove spans from cache (or mark as reported)!!!

        reportBatchToDam(batchBuilder);
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Can't wrap the lineage creation for Kensu, see exception", e);
    }
  }

  protected void reportBatchToDam(DamBatchBuilder batchBuilder){
    try {
      ApiClient apiClient;
      if (damEnv.isOffline()) {
        apiClient = new OfflineFileApiClient();
      } else {
        String authToken = damEnv.damIngestionToken();
        String serverHost = damEnv.damIngestionUrl();
        apiClient = new ApiClient()
          .setBasePath(serverHost)
          .addDefaultHeader("X-Auth-Token", authToken);
      }
      ManageKensuDamEntitiesApi apiInstance = new ManageKensuDamEntitiesApi(apiClient);
      BatchEntityReportResult result = apiInstance.reportEntityBatch(batchBuilder.getCompactedBatch());
      logger.info(String.format("DAM reportEntityBatch result: %s", result));
    } catch (javax.ws.rs.ProcessingException e){
      if (e.getMessage().equals("Already connected")){
        logger.log(Level.SEVERE, "Exception when calling ManageKensuDAMEntitiesApi#reportEntityBatch - SSL verification issue:", e);
      } else {
        logger.log(Level.SEVERE, "Exception when calling ManageKensuDAMEntitiesApi#reportEntityBatch", e);
      }
    } catch (ApiException | RuntimeException e) {
      logger.log(Level.SEVERE, "Exception when calling ManageKensuDAMEntitiesApi#reportEntityBatch", e);
    }
  }

  private String createLogMessage(Instant timestamp, String step, Span span) {
    StringBuilder sb = new StringBuilder();
    sb.append("{"+timestamp+"} "+step+" span " + span.id()  + " of trace " + span.traceId() + " for operation " + span.name() + "\n");
    sb.append(" + tags:" + "\n");
    for (Map.Entry<String, String> entry : span.tags().entrySet()) {
      sb.append("   - " + entry.getKey() + ": " + entry.getValue() + "\n");
    }
    // FIXME
//    sb.append(" + references:" + "\n");
//    for (Map.Entry<String, String> entry : span.annotations().entrySet()) {
//      sb.append("   - " + entry.getKey() + ": " + entry.getValue() + "\n");
//    }

    return sb.toString();
  }

  private String createLogMessage(Instant timestamp, String step, Span span, Map<String, ?> fields) {
    StringBuilder sb = new StringBuilder(createLogMessage(timestamp, step, span));
    sb.append(" + fields" + "\n");
    for (Map.Entry<String, ?> entry : fields.entrySet()) {
      sb.append("   - " + entry.getKey() + ": " + entry.getValue() + "\n");
    }
    return sb.toString();
  }

  // TODO maybe another one that would also benefits from the `Mapper` information in Hibernate (using Interceptors)?
  // TODO external mapping file (manually or generated?)
  public final static Lineages.ProcessCatatalogEntry OUT_ENDS_WITH_IN = new Lineages.ProcessCatatalogEntry(java.util.Collections.singletonList(new Lineages.NoCheckProcessCatalogMapping() {
    public Map<Entry<DataSources.DataCatalogEntry, String>, Set<Entry<DataSources.DataCatalogEntry, String>>> apply(DataSources.DataCatalogEntry i, DataSources.DataCatalogEntry o) {
      Map<Entry<DataSources.DataCatalogEntry, String>, Set<Entry<DataSources.DataCatalogEntry, String>>> results =
        new java.util.HashMap<>();

      for (FieldDef	ofd : o.fields) {
        Entry<DataSources.DataCatalogEntry, String> op = new AbstractMap.SimpleEntry<>(o, ofd.getName());
        Entry<DataSources.DataCatalogEntry, String> ip = null;
        for (FieldDef ifd: i.fields) {
          // if (ifd.getName().equals(ofd.getName())) {
          if (ofd.getName().equals(ifd.getName()) || ofd.getName().endsWith("."+ifd.getName())) {
            ip = new AbstractMap.SimpleImmutableEntry<>(i, ifd.getName());
            break;
          }
        }
        if (ip != null) {
          Set<Entry<DataSources.DataCatalogEntry, String>> ips = new java.util.HashSet<>();
          ips.add(ip);
          results.put(op, ips);
        }
      }
      return results;
    }
  }));
}

