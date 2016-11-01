package com.fivetran.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.Source;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.*;
import org.apache.calcite.avatica.remote.TypedValue;
import org.apache.calcite.config.Lex;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlValidatorImpl;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.util.Util;

import java.lang.reflect.Type;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

class TruffleMeta extends MetaImpl {
    public TruffleMeta(AvaticaConnection connection) {
        super(connection);
    }

    @Override
    public StatementHandle prepare(ConnectionHandle ch, String sql, long maxRowCount) {
        StatementHandle statement = createStatement(ch);

        statement.signature = validate(sql, parse(sql));

        return statement;
    }

    private SqlNode parse(String sql) {
        try {
            SqlParser.Config config = SqlParser.configBuilder().setLex(Lex.JAVA).build();
            SqlParser parser = SqlParser.create(sql, config);

            return parser.parseStmt();
        } catch (SqlParseException e) {
            throw new RuntimeException(e);
        }
    }

    private Signature validate(String query, SqlNode parsed) {
        SqlValidatorImpl validator = validator();

        // Validate the query
        // Has the side-effect of storing the RelDataType in an internal cache of validator
        validator.validate(parsed);

        // Type of query
        RelDataType type = validator.getValidatedNodeType(parsed);
        // Fully-qualified-name of each field of query
        List<List<String>> fieldOrigins = validator.getFieldOrigins(parsed);

        // Convert list of RelDataTypeField to list of ColumnMetaData
        List<RelDataTypeField> fieldList = type.getFieldList();
        List<ColumnMetaData> columns = new ArrayList<>();
        for (int i = 0; i < fieldList.size(); i++) {
            RelDataTypeField field = fieldList.get(i);
            ColumnMetaData metaData = metaData(
                    typeFactory(),
                    i,
                    field.getName(),
                    field.getType(),
                    null,
                    fieldOrigins.get(i)
            );

            columns.add(metaData);
        }
        return new Signature(
                columns,
                query,
                Collections.emptyList(), // Root query takes no parameters
                Collections.emptyMap(), // No internal parameters to keep track of
                CursorFactory.ARRAY,
                StatementType.SELECT // For now we only do SELECT queries
        );
    }

    private SqlValidatorImpl validator() {
        // .instance() initializes SqlStdOperatorTable by scanning its own public static fields
        SqlStdOperatorTable ops = SqlStdOperatorTable.instance();

        return new SqlValidatorImpl(ops, catalogReader(), typeFactory(), SqlConformance.PRAGMATIC_2003) {
            // No overrides
        };
    }

    /**
     * Exposed for mocking during tests
     */
    static Function<TruffleMeta, Prepare.CatalogReader> catalogReader = TruffleMeta::doCatalogReader;

    private Prepare.CatalogReader catalogReader() {
        return catalogReader.apply(this);
    }

    private Prepare.CatalogReader doCatalogReader() {
        JavaTypeFactory types = typeFactory();
        CalciteSchema rootSchema = CalciteSchema.createRootSchema(false);

        return new CalciteCatalogReader(rootSchema, true, Collections.emptyList(), types);
    }

    public static JavaTypeFactory typeFactory() {
        return new JavaTypeFactoryImpl();
    }

    private RelRoot expandView(RelDataType rowType, String queryString, List<String> schemaPath, List<String> viewPath) {
        throw new UnsupportedOperationException();
    }

    private RelRoot plan(SqlNode parsed) {
        VolcanoPlanner planner = new VolcanoPlanner(null, new TrufflePlannerContext());
        RelOptCluster cluster = RelOptCluster.create(planner, new RexBuilder(typeFactory()));
        SqlToRelConverter.Config config = SqlToRelConverter.configBuilder().withTrimUnusedFields(true).build();
        SqlToRelConverter converter = new SqlToRelConverter(
                this::expandView,
                validator(),
                catalogReader(),
                cluster,
                StandardConvertletTable.INSTANCE,
                config
        );

        return converter.convertQuery(parsed, true, true);
    }

    private final Map<StatementHandle, Running> runningQueries = new ConcurrentHashMap<>();

    private static class Running {
        public final List<Object[]> rows;
        public final RelDataType type;

        private Running(List<Object[]> rows, RelDataType type) {
            this.rows = rows;
            this.type = type;
        }
    }

    /**
     * Start running a query
     */
    private void start(StatementHandle handle, RelRoot plan) {
        Source source = Source.newBuilder(plan.toString())
                .mimeType(TruffleSqlLanguage.MIME_TYPE)
                .name("?")
                .build();

        // Create a program that sticks query results in a list
        List<Object[]> results = new ArrayList<>();
        FrameDescriptor resultFrame = com.fivetran.truffle.Types.frame(plan.validatedRowType);
        RowSink then = new RowSink(resultFrame) {
            @Override
            public void executeVoid(VirtualFrame frame) {
                List<? extends FrameSlot> slots = resultFrame.getSlots();
                Object[] values = new Object[slots.size()];

                for (int i = 0; i < slots.size(); i++) {
                    FrameSlot slot = slots.get(i);

                    try {
                        values[i] = frame.getObject(slot);
                    } catch (FrameSlotTypeException e) {
                        throw new RuntimeException(e);
                    }
                }

                results.add(values);
            }
        };

        // Compile the query plan into a Truffle program
        CallTarget program = TruffleSqlLanguage.INSTANCE.parse(source, new ExprPlan(plan, then));

        Main.callWithRootContext(program);

        // Stash the list so fetch(StatementHandle) can get it
        runningQueries.put(handle, new Running(results, plan.validatedRowType));
    }

    @Override
    public ExecuteResult prepareAndExecute(StatementHandle h,
                                           String sql,
                                           long maxRowCount,
                                           PrepareCallback callback) throws NoSuchStatementException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ExecuteResult prepareAndExecute(StatementHandle h,
                                           String sql,
                                           long maxRowCount,
                                           int maxRowsInFirstFrame,
                                           PrepareCallback callback) throws NoSuchStatementException {
        SqlNode parsed = parse(sql);
        Signature signature = validate(sql, parsed);
        RelRoot plan = plan(parsed);

        start(h, plan);

        try {
            synchronized (callback.getMonitor()) {
                callback.clear();
                callback.assign(signature, null, -1);
            }

            callback.execute();

            MetaResultSet metaResultSet = MetaResultSet.create(h.connectionId, h.id, false, signature, null);

            return new ExecuteResult(Collections.singletonList(metaResultSet));
        } catch(SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ExecuteBatchResult prepareAndExecuteBatch(StatementHandle h,
                                                     List<String> sqlCommands) throws NoSuchStatementException {
        return null;
    }

    @Override
    public ExecuteBatchResult executeBatch(StatementHandle h,
                                           List<List<TypedValue>> parameterValues) throws NoSuchStatementException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Frame fetch(StatementHandle h,
                       long offset,
                       int fetchMaxRowCount) throws NoSuchStatementException, MissingResultsException {
        Running running = runningQueries.get(h);
        List<Object> slice = running.rows
                .stream()
                .skip(offset)
                .limit(fetchMaxRowCount)
                .map(row -> {
                    Object[] after = new Object[row.length];

                    for (int column = 0; column < row.length; column++) {
                        RelDataType type = running.type.getFieldList().get(column).getType();

                        after[column] = com.fivetran.truffle.Types.resultSet(row[column], type);
                    }

                    return after;
                })
                .collect(Collectors.toList());

        return new Frame(offset, slice.isEmpty(), slice);
    }

    @Override
    public ExecuteResult execute(StatementHandle h,
                                 List<TypedValue> parameterValues,
                                 long maxRowCount) throws NoSuchStatementException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ExecuteResult execute(StatementHandle h,
                                 List<TypedValue> parameterValues,
                                 int maxRowsInFirstFrame) throws NoSuchStatementException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void closeStatement(StatementHandle h) {
        // Nothing to do
    }

    @Override
    public boolean syncResults(StatementHandle sh,
                               QueryState state,
                               long offset) throws NoSuchStatementException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void commit(ConnectionHandle ch) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rollback(ConnectionHandle ch) {
        throw new UnsupportedOperationException();
    }

    private static ColumnMetaData metaData(JavaTypeFactory typeFactory,
                                           int ordinal,
                                           String fieldName,
                                           RelDataType type,
                                           RelDataType fieldType,
                                           List<String> origins) {
        type = com.fivetran.truffle.Types.simplify(type);

        final ColumnMetaData.AvaticaType avaticaType =
                avaticaType(typeFactory, type, fieldType);
        return new ColumnMetaData(
                ordinal,
                false,
                true,
                false,
                false,
                type.isNullable()
                        ? DatabaseMetaData.columnNullable
                        : DatabaseMetaData.columnNoNulls,
                true,
                type.getPrecision(),
                fieldName,
                origin(origins, 0),
                origin(origins, 2),
                getPrecision(type),
                getScale(type),
                origin(origins, 1),
                null,
                avaticaType,
                true,
                false,
                false,
                avaticaType.columnClassName());
    }

    private static ColumnMetaData.AvaticaType avaticaType(JavaTypeFactory typeFactory,
                                                          RelDataType type,
                                                          RelDataType fieldType) {
        final String typeName = getTypeName(type);
        if (type.getComponentType() != null) {
            final ColumnMetaData.AvaticaType componentType =
                    avaticaType(typeFactory, type.getComponentType(), null);
            final Type clazz = typeFactory.getJavaClass(type.getComponentType());
            final ColumnMetaData.Rep rep = ColumnMetaData.Rep.of(clazz);
            assert rep != null;
            return ColumnMetaData.array(componentType, typeName, rep);
        } else {
            final int typeOrdinal = getTypeOrdinal(type);
            switch (typeOrdinal) {
                case Types.STRUCT:
                    final List<ColumnMetaData> columns = new ArrayList<>();
                    for (RelDataTypeField field : type.getFieldList()) {
                        columns.add(metaData(typeFactory, field.getIndex(), field.getName(), field.getType(), null, null));
                    }
                    return ColumnMetaData.struct(columns);
                default:
                    final Type clazz =
                            typeFactory.getJavaClass(Util.first(fieldType, type));
                    final ColumnMetaData.Rep rep = ColumnMetaData.Rep.of(clazz);
                    assert rep != null;
                    return ColumnMetaData.scalar(typeOrdinal, typeName, rep);
            }
        }
    }

    private static String origin(List<String> origins, int offsetFromEnd) {
        return origins == null || offsetFromEnd >= origins.size()
                ? null
                : origins.get(origins.size() - 1 - offsetFromEnd);
    }

    private static int getTypeOrdinal(RelDataType type) {
        return type.getSqlTypeName().getJdbcOrdinal();
    }

    /** Returns the type name in string form. Does not include precision, scale
     * or whether nulls are allowed. Example: "DECIMAL" not "DECIMAL(7, 2)";
     * "INTEGER" not "JavaType(int)". */
    private static String getTypeName(RelDataType type) {
        final SqlTypeName sqlTypeName = type.getSqlTypeName();
        switch (sqlTypeName) {
            case ARRAY:
            case MULTISET:
            case MAP:
            case ROW:
                return type.toString(); // e.g. "INTEGER ARRAY"
            case INTERVAL_YEAR_MONTH:
                return "INTERVAL_YEAR_TO_MONTH";
            case INTERVAL_DAY_HOUR:
                return "INTERVAL_DAY_TO_HOUR";
            case INTERVAL_DAY_MINUTE:
                return "INTERVAL_DAY_TO_MINUTE";
            case INTERVAL_DAY_SECOND:
                return "INTERVAL_DAY_TO_SECOND";
            case INTERVAL_HOUR_MINUTE:
                return "INTERVAL_HOUR_TO_MINUTE";
            case INTERVAL_HOUR_SECOND:
                return "INTERVAL_HOUR_TO_SECOND";
            case INTERVAL_MINUTE_SECOND:
                return "INTERVAL_MINUTE_TO_SECOND";
            default:
                return sqlTypeName.getName(); // e.g. "DECIMAL", "INTERVAL_YEAR_MONTH"
        }
    }

    private static int getScale(RelDataType type) {
        return type.getScale() == RelDataType.SCALE_NOT_SPECIFIED
                ? 0
                : type.getScale();
    }

    private static int getPrecision(RelDataType type) {
        return type.getPrecision() == RelDataType.PRECISION_NOT_SPECIFIED
                ? 0
                : type.getPrecision();
    }
}
