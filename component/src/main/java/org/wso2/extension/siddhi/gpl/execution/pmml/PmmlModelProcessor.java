/*
 * Copyright (C) 2017 WSO2 Inc. (http://wso2.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.wso2.extension.siddhi.gpl.execution.pmml;

import org.apache.log4j.Logger;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.PMML;
import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.EvaluatorUtil;
import org.jpmml.evaluator.FieldValue;
import org.jpmml.evaluator.InputField;
import org.jpmml.evaluator.InvalidResultException;
import org.jpmml.evaluator.ModelEvaluator;
import org.jpmml.evaluator.ModelEvaluatorFactory;
import org.jpmml.evaluator.OutputField;
import org.jpmml.evaluator.PMMLException;
import org.jpmml.evaluator.TargetField;
import org.wso2.extension.siddhi.gpl.execution.pmml.util.PMMLUtil;
import org.wso2.siddhi.annotation.Example;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.annotation.Parameter;
import org.wso2.siddhi.annotation.ReturnAttribute;
import org.wso2.siddhi.annotation.util.DataType;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventCloner;
import org.wso2.siddhi.core.event.stream.populater.ComplexEventPopulater;
import org.wso2.siddhi.core.exception.SiddhiAppCreationException;
import org.wso2.siddhi.core.exception.SiddhiAppRuntimeException;
import org.wso2.siddhi.core.executor.ConstantExpressionExecutor;
import org.wso2.siddhi.core.executor.ExpressionExecutor;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.query.processor.Processor;
import org.wso2.siddhi.core.query.processor.stream.StreamProcessor;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.query.api.definition.AbstractDefinition;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.exception.SiddhiAppValidationException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Class implementing Pmml Model Processor.
 */
@Extension(
        name = "predict",
        namespace = "pmml",
        description = "This extension processes the input stream attributes according to the defined PMML standard " +
                "model and outputs the processed results together with the input stream attributes.",
        parameters = {
                @Parameter(
                        name = "path.to.pmml.file",
                        description = "The path to the PMML model file.\n",
                        type = {DataType.STRING}
                ),
                @Parameter(
                        name = "input",
                        description = "An attribute of the input stream that is sent to the PMML standard model " +
                                "as a value to based on which the prediction is made. The predict function does " +
                                "not accept any constant values as input parameters. You can have multiple input " +
                                "parameters according to the input stream definition.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "Empty Array"
                )
        },
        returnAttributes = {
                @ReturnAttribute(
                        name = "output",
                        description = "All the processed outputs defined in the query. The number of outputs can " +
                                "vary depending on the query definition.",
                        type = {DataType.STRING, DataType.INT, DataType.DOUBLE, DataType.FLOAT, DataType.BOOL}
                )
        },
        examples = {
                @Example(
                        syntax = "predict('<SP HOME>/samples/artifacts/0301/decision-tree.pmml', root_shell, " +
                                "su_attempted, num_root, num_file_creations, num_shells, num_access_files, " +
                                "num_outbound_cmds, is_host_login, is_guest_login , count, srv_count, serror_rate, " +
                                "srv_serror_rate)",
                        description = "This model is implemented to detect network intruders. The input event " +
                                "stream is processed by the execution plan that uses the pmml predictive model " +
                                "to detect whether a particular user is an intruder to the network or not. The " +
                                "output stream contains the processed query results that include the predicted " +
                                "responses."
                )
        }
)
public class PmmlModelProcessor extends StreamProcessor {

    private static final Logger logger = Logger.getLogger(PmmlModelProcessor.class);

    private String pmmlDefinition;
    private boolean attributeSelectionAvailable;
    // <feature-name, [event-array-type][attribute-index]> pairs
    private Map<InputField, int[]> attributeIndexMap;
    // All the input fields defined in the pmml definition
    private List<InputField> inputFields;
    // Output fields of the pmml definition
    private Map<FieldName, org.dmg.pmml.DataType> outputFields = new LinkedHashMap<>();
    private Evaluator evaluator;

    @Override
    protected List<Attribute> init(AbstractDefinition abstractDefinition,
                                   ExpressionExecutor[] expressionExecutors,
                                   ConfigReader configReader, SiddhiAppContext siddhiAppContext) {

        if (attributeExpressionExecutors.length == 0) {
            throw new SiddhiAppValidationException("PMML model definition not available.");
        } else {
            attributeSelectionAvailable = attributeExpressionExecutors.length != 1;
        }

        // Check whether the first parameter in the expression is the pmml definition
        if (attributeExpressionExecutors[0] instanceof ConstantExpressionExecutor) {
            Object constantObj = ((ConstantExpressionExecutor) attributeExpressionExecutors[0]).getValue();
            pmmlDefinition = (String) constantObj;
        } else {
            throw new SiddhiAppValidationException("PMML model definition has not been set as the first parameter");
        }

        // Unmarshal the definition and get an executable pmml model
        PMML pmml = PMMLUtil.unmarshal(pmmlDefinition);
        ModelEvaluatorFactory modelEvaluatorFactory = ModelEvaluatorFactory.newInstance();
        ModelEvaluator<?> modelEvaluator = modelEvaluatorFactory.newModelEvaluator(pmml);
        evaluator = (Evaluator) modelEvaluator;

        inputFields = evaluator.getActiveFields();
        if (evaluator.getOutputFields().size() == 0) {
            List<TargetField> targetFields = evaluator.getTargetFields();
            for (TargetField targetField : targetFields) {
                outputFields.put(targetField.getName(), targetField.getDataType());
            }
        } else {
            List<OutputField> outputFields = evaluator.getOutputFields();
            for (OutputField outputField : outputFields) {
                this.outputFields.put(outputField.getName(), outputField.getDataType());
            }
        }

        return generateOutputAttributes();
    }

    @Override
    protected void process(ComplexEventChunk<StreamEvent> streamEventChunk, Processor nextProcessor,
                           StreamEventCloner streamEventCloner, ComplexEventPopulater complexEventPopulater) {

        StreamEvent event = streamEventChunk.getFirst();
        Map<FieldName, FieldValue> inData = new HashMap<>();

        for (Map.Entry<InputField, int[]> entry : attributeIndexMap.entrySet()) {
            InputField inputField = entry.getKey();
            int[] attributeIndexArray = entry.getValue();
            Object dataValue = null;
            switch (attributeIndexArray[2]) {
                case 0:
                    dataValue = event.getBeforeWindowData()[attributeIndexArray[3]];
                    break;
                case 2:
                    dataValue = event.getOutputData()[attributeIndexArray[3]];
                    break;
                default:
                    break;
            }
            try {
                inData.put(inputField.getName(), inputField.prepare(String.valueOf(dataValue)));
            } catch (InvalidResultException e) {
                logger.error(String.format("Incompatible value for field: %s. Prediction might be erroneous.",
                        inputField.getName()), e);
                throw new SiddhiAppRuntimeException(String.format("Incompatible value for field: %s. Prediction might "
                        + "be erroneous.", inputField.getName()), e);
            }
        }

        if (!inData.isEmpty()) {
            try {
                Map<FieldName, ?> result = evaluator.evaluate(inData);
                Object[] output = new Object[outputFields.size()];
                int i = 0;
                for (FieldName fieldName : outputFields.keySet()) {
                    if (result.containsKey(fieldName)) {
                        Object value = result.get(fieldName);
                        output[i] = EvaluatorUtil.decode(value);
                        i++;
                    }
                }
                complexEventPopulater.populateComplexEvent(event, output);
                nextProcessor.process(streamEventChunk);
            } catch (PMMLException e) {
                logger.error("Error while predicting. Invalid result occurred while evaluating the model", e);
                throw new SiddhiAppRuntimeException("Error while predicting", e);
            }
        }
    }

    @Override
    public void start() {

        try {
            populateFeatureAttributeMapping();
        } catch (Exception e) {
            logger.error("Error while mapping attributes with pmml model features : " + pmmlDefinition, e);
            throw new SiddhiAppCreationException("Error while mapping attributes with pmml model features : " +
                    pmmlDefinition + "\n" + e.getMessage());
        }
    }

    /**
     * Match the attribute index values of stream with feature names of the model.
     *
     * @throws Exception ExceutionPlanCreationException
     */
    private void populateFeatureAttributeMapping() throws Exception {

        attributeIndexMap = new HashMap<>();
        HashMap<String, InputField> features = new HashMap<>();

        for (InputField inputField : inputFields) {
            features.put(inputField.getName().getValue(), inputField);
        }

        if (attributeSelectionAvailable) {
            for (ExpressionExecutor expressionExecutor : attributeExpressionExecutors) {
                if (expressionExecutor instanceof VariableExpressionExecutor) {
                    VariableExpressionExecutor variable = (VariableExpressionExecutor) expressionExecutor;
                    String variableName = variable.getAttribute().getName();
                    if (features.get(variableName) != null) {
                        attributeIndexMap.put(features.get(variableName), variable.getPosition());
                    } else {
                        throw new SiddhiAppCreationException("No matching feature name found in the model " +
                                "for the attribute : " + variableName);
                    }
                }
            }
        } else {
            String[] attributeNames = inputDefinition.getAttributeNameArray();
            for (String attributeName : attributeNames) {
                if (features.get(attributeName) != null) {
                    int[] attributeIndexArray = new int[4];
                    attributeIndexArray[2] = 2; // get values from output data
                    attributeIndexArray[3] = inputDefinition.getAttributePosition(attributeName);
                    attributeIndexMap.put(features.get(attributeName), attributeIndexArray);
                } else {
                    throw new SiddhiAppCreationException("No matching feature name found in the model " +
                            "for the attribute : " + attributeName);
                }
            }
        }
    }

    /**
     * Generate the output attribute list.
     *
     * @return List Siddhi Output Attribute List
     */
    private List<Attribute> generateOutputAttributes() {

        List<Attribute> outputAttributes = new ArrayList<>();
        for (Map.Entry<FieldName, org.dmg.pmml.DataType> entry : outputFields.entrySet()) {
            FieldName fieldName = entry.getKey();
            org.dmg.pmml.DataType dataType = entry.getValue();
            if (dataType == null) {
                dataType = org.dmg.pmml.DataType.STRING;
            }
            outputAttributes.add(new Attribute(fieldName.getValue(), mapOutputAttributes(dataType)));
        }
        return outputAttributes;
    }

    /**
     * Map the model output fields to Siddhi Attributes.
     *
     * @return Attribute.Type Mapped Siddhi Attribute
     */
    private Attribute.Type mapOutputAttributes(org.dmg.pmml.DataType dataType) {

        Attribute.Type type = null;
        if (dataType.equals(org.dmg.pmml.DataType.DOUBLE)) {
            type = Attribute.Type.DOUBLE;
        } else if (dataType.equals(org.dmg.pmml.DataType.FLOAT)) {
            type = Attribute.Type.FLOAT;
        } else if (dataType.equals(org.dmg.pmml.DataType.INTEGER)) {
            type = Attribute.Type.INT;
        } else if (dataType.equals(org.dmg.pmml.DataType.BOOLEAN)) {
            type = Attribute.Type.BOOL;
        } else if (dataType.equals(org.dmg.pmml.DataType.STRING)) {
            type = Attribute.Type.STRING;
        } else {
            throw new IllegalArgumentException("Output type " + dataType + " is not supported by the extension");
        }
        return type;
    }

    @Override
    public void stop() {
    }

    @Override
    public Map<String, Object> currentState() {
        return new HashMap<>();
    }

    @Override
    public void restoreState(Map<String, Object> map) {
    }
}
