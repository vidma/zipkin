package io.kensu.collector.utils.jdbc.parser;

import io.kensu.collector.utils.ConcurrentHashMultimap;
import io.kensu.dam.model.FieldDef;

import java.util.Map;

public class ReferencedSchemaFieldsInfo {
    public final ConcurrentHashMultimap<Map.Entry<FieldDef, String>> data;
    public final ConcurrentHashMultimap<FieldDef> control, schema;
    public final String lineageOperation;

    public ReferencedSchemaFieldsInfo(String lineageOperation, ConcurrentHashMultimap<Map.Entry<FieldDef, String>> data, ConcurrentHashMultimap<FieldDef> control){
        this.lineageOperation = lineageOperation;
        this.data = data;
        this.control = control;
        this.schema = new ConcurrentHashMultimap<FieldDef>();
        data.forEach((s, fs) -> fs.forEach(f -> this.schema.addEntry(s, f.getKey())));
        control.forEach((s, fs) -> fs.forEach(f -> this.schema.addEntry(s, f)));
    }
}
