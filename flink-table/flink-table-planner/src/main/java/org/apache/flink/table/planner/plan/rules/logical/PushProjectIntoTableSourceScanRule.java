/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.plan.rules.logical;

import org.apache.flink.table.api.TableConfig;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.abilities.SupportsProjectionPushDown;
import org.apache.flink.table.connector.source.abilities.SupportsReadingMetadata;
import org.apache.flink.table.planner.calcite.FlinkTypeFactory;
import org.apache.flink.table.planner.connectors.DynamicSourceUtils;
import org.apache.flink.table.planner.plan.abilities.source.ProjectPushDownSpec;
import org.apache.flink.table.planner.plan.abilities.source.ReadingMetadataSpec;
import org.apache.flink.table.planner.plan.abilities.source.SourceAbilityContext;
import org.apache.flink.table.planner.plan.abilities.source.SourceAbilitySpec;
import org.apache.flink.table.planner.plan.schema.TableSourceTable;
import org.apache.flink.table.planner.plan.utils.NestedColumn;
import org.apache.flink.table.planner.plan.utils.NestedProjectionUtil;
import org.apache.flink.table.planner.plan.utils.NestedSchema;
import org.apache.flink.table.planner.plan.utils.RexNodeExtractor;
import org.apache.flink.table.planner.utils.ShortcutUtils;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.utils.DataTypeUtils;
import org.apache.flink.table.types.utils.TypeConversions;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.rules.ProjectRemoveRule;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Planner rule that pushes a {@link LogicalProject} into a {@link LogicalTableScan} which wraps a
 * {@link SupportsProjectionPushDown} dynamic table source.
 */
public class PushProjectIntoTableSourceScanRule extends RelOptRule {
    public static final PushProjectIntoTableSourceScanRule INSTANCE =
            new PushProjectIntoTableSourceScanRule();

    public PushProjectIntoTableSourceScanRule() {
        super(
                operand(LogicalProject.class, operand(LogicalTableScan.class, none())),
                "PushProjectIntoTableSourceScanRule");
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
        LogicalTableScan scan = call.rel(1);
        TableSourceTable tableSourceTable = scan.getTable().unwrap(TableSourceTable.class);
        if (tableSourceTable == null
                || !(tableSourceTable.tableSource() instanceof SupportsProjectionPushDown)) {
            return false;
        }
        return Arrays.stream(tableSourceTable.abilitySpecs())
                .noneMatch(spec -> spec instanceof ProjectPushDownSpec);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        final LogicalProject project = call.rel(0);
        final LogicalTableScan scan = call.rel(1);

        final int[] refFields = RexNodeExtractor.extractRefInputFields(project.getProjects());
        TableSourceTable oldTableSourceTable = scan.getTable().unwrap(TableSourceTable.class);
        final ResolvedSchema oldSchema = oldTableSourceTable.catalogTable().getResolvedSchema();
        final DynamicTableSource oldSource = oldTableSourceTable.tableSource();
        final TableConfig config = ShortcutUtils.unwrapContext(scan).getTableConfig();

        final boolean supportsNestedProjection =
                ((SupportsProjectionPushDown) oldTableSourceTable.tableSource())
                        .supportsNestedProjection();
        List<String> fieldNames = scan.getRowType().getFieldNames();

        if (!supportsNestedProjection && refFields.length == fieldNames.size()) {
            // just keep as same as the old plan
            // TODO: refactor the affected plan
            return;
        }

        List<RexNode> oldProjectsWithPK = new ArrayList<>(project.getProjects());
        FlinkTypeFactory flinkTypeFactory = ShortcutUtils.unwrapTypeFactory(scan);
        if (isPrimaryKeyFieldsRequired(oldTableSourceTable, config)) {
            // add pk into projects for upsert source
            oldSchema
                    .getPrimaryKey()
                    .ifPresent(
                            pks -> {
                                for (String name : pks.getColumns()) {
                                    int index = fieldNames.indexOf(name);
                                    Column col = oldSchema.getColumn(index).get();
                                    oldProjectsWithPK.add(
                                            new RexInputRef(
                                                    index,
                                                    flinkTypeFactory.createFieldTypeFromLogicalType(
                                                            col.getDataType().getLogicalType())));
                                }
                            });
        }
        // build used schema tree
        RowType originType = DynamicSourceUtils.createProducedType(oldSchema, oldSource);
        NestedSchema nestedSchema =
                NestedProjectionUtil.build(
                        oldProjectsWithPK, flinkTypeFactory.buildRelNodeRowType(originType));
        if (!supportsNestedProjection) {
            // mark the fields in the top level as leaf
            for (NestedColumn column : nestedSchema.columns().values()) {
                column.markLeaf();
            }
        }
        DataType producedDataType = TypeConversions.fromLogicalToDataType(originType);

        List<SourceAbilitySpec> sourceAbilitySpecs = new ArrayList<>();
        RowType newProducedType;
        if (oldSource instanceof SupportsReadingMetadata) {
            List<String> metadataKeys =
                    DynamicSourceUtils.createRequiredMetadataKeys(oldSchema, oldSource);
            newProducedType =
                    applyPhysicalAndMetadataPushDown(
                            nestedSchema, metadataKeys, originType, sourceAbilitySpecs);
        } else {
            int[][] projectedFields = NestedProjectionUtil.convertToIndexArray(nestedSchema);
            newProducedType =
                    (RowType)
                            DataTypeUtils.projectRow(producedDataType, projectedFields)
                                    .getLogicalType();
            sourceAbilitySpecs.add(new ProjectPushDownSpec(projectedFields, newProducedType));
        }

        DynamicTableSource newSource = oldSource.copy();
        SourceAbilityContext context = SourceAbilityContext.from(scan);
        for (SourceAbilitySpec abilitySpec : sourceAbilitySpecs) {
            abilitySpec.apply(newSource, context);
        }

        RelDataType newRowType = flinkTypeFactory.buildRelNodeRowType(newProducedType);

        // project push down does not change the statistic, we can reuse origin statistic
        TableSourceTable newTableSourceTable =
                oldTableSourceTable.copy(
                        newSource,
                        newRowType,
                        getExtraDigests(newRowType, sourceAbilitySpecs),
                        sourceAbilitySpecs.toArray(new SourceAbilitySpec[0]));
        LogicalTableScan newScan =
                new LogicalTableScan(
                        scan.getCluster(),
                        scan.getTraitSet(),
                        scan.getHints(),
                        newTableSourceTable);
        // rewrite the input field in projections
        // the origin projections are enough. Because the upsert source only uses pk info
        // normalization node.
        List<RexNode> newProjects =
                NestedProjectionUtil.rewrite(
                        project.getProjects(), nestedSchema, call.builder().getRexBuilder());
        // rewrite new source
        LogicalProject newProject =
                project.copy(project.getTraitSet(), newScan, newProjects, project.getRowType());

        if (ProjectRemoveRule.isTrivial(newProject)) {
            // drop project if the transformed program merely returns its input
            call.transformTo(newScan);
        } else {
            call.transformTo(newProject);
        }
    }

    private static String[] getExtraDigests(
            RelDataType rowType, List<SourceAbilitySpec> abilitySpecs) {
        final List<String> digests = new ArrayList<>();
        digests.add(String.format("project=[%s]", String.join(", ", rowType.getFieldNames())));

        for (SourceAbilitySpec abilitySpec : abilitySpecs) {
            if (abilitySpec instanceof ReadingMetadataSpec) {
                final ReadingMetadataSpec metadataSpec = (ReadingMetadataSpec) abilitySpec;
                digests.add(
                        String.format(
                                "metadata=[%s]",
                                String.join(", ", metadataSpec.getMetadataKeys())));
            }
        }

        return digests.toArray(new String[0]);
    }

    /** Returns true if the primary key is required and should be retained. */
    private static boolean isPrimaryKeyFieldsRequired(TableSourceTable table, TableConfig config) {
        return DynamicSourceUtils.isUpsertSource(table.catalogTable(), table.tableSource())
                || DynamicSourceUtils.isSourceChangeEventsDuplicate(
                        table.catalogTable(), table.tableSource(), config);
    }

    /**
     * Push the used physical column and metadata into table source. The returned value is used to
     * build new table schema.
     */
    private static RowType applyPhysicalAndMetadataPushDown(
            NestedSchema nestedSchema,
            List<String> metadataKeys,
            RowType originType,
            List<SourceAbilitySpec> sourceAbilitySpecs) {
        // TODO: supports nested projection for metadata
        List<NestedColumn> usedMetaDataFields = new LinkedList<>();
        int physicalCount = originType.getFieldCount() - metadataKeys.size();
        List<String> fieldNames = originType.getFieldNames();

        // rm metadata in the tree
        for (int i = 0; i < metadataKeys.size(); i++) {
            NestedColumn usedMetadata =
                    nestedSchema.columns().remove(fieldNames.get(i + physicalCount));
            if (usedMetadata != null) {
                usedMetaDataFields.add(usedMetadata);
            }
        }

        // get path of the used fields
        int[][] projectedPhysicalFields = NestedProjectionUtil.convertToIndexArray(nestedSchema);

        // push the metadata back for later rewrite and extract the location in the origin row
        int newIndex = projectedPhysicalFields.length;
        List<String> usedMetadataNames = new LinkedList<>();
        for (NestedColumn metadata : usedMetaDataFields) {
            metadata.setIndexOfLeafInNewSchema(newIndex++);
            nestedSchema.columns().put(metadata.name(), metadata);
            usedMetadataNames.add(metadataKeys.get(metadata.indexInOriginSchema() - physicalCount));
        }

        // apply metadata push down
        int[][] projectedFields =
                Stream.concat(
                                Stream.of(projectedPhysicalFields),
                                usedMetaDataFields.stream()
                                        .map(field -> new int[] {field.indexInOriginSchema()}))
                        .toArray(int[][]::new);
        RowType newProducedType =
                (RowType)
                        DataTypeUtils.projectRow(
                                        TypeConversions.fromLogicalToDataType(originType),
                                        projectedFields)
                                .getLogicalType();

        sourceAbilitySpecs.add(new ProjectPushDownSpec(projectedPhysicalFields, newProducedType));
        sourceAbilitySpecs.add(new ReadingMetadataSpec(usedMetadataNames, newProducedType));
        return newProducedType;
    }
}
