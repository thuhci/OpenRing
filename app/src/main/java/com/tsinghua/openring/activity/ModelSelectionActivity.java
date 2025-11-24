package com.tsinghua.openring.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.tsinghua.openring.R;
import com.tsinghua.openring.inference.ModelArchitecture;
import com.tsinghua.openring.inference.ModelInferenceManager;
import com.tsinghua.openring.inference.ModelSelectionConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Model Selection Activity
 * Allows users to select different model architectures for different vital signs
 */
public class ModelSelectionActivity extends AppCompatActivity {

    public static final String EXTRA_CONFIG = "model_config";

    private ModelSelectionConfig config;
    private Map<ModelInferenceManager.Mission, Spinner> missionSpinners = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_selection);

        // Get current configuration or create new one
        ModelSelectionConfig provided = (ModelSelectionConfig) getIntent().getSerializableExtra(EXTRA_CONFIG);
        config = provided != null ? provided : new ModelSelectionConfig();

        setupUI();
    }

    private void setupUI() {
        // Set title
        TextView titleView = findViewById(R.id.tv_model_selection_title);
        titleView.setText("Model Architecture Selection");

        // Setup spinners for each mission
        setupMissionSpinner(R.id.spinner_hr, R.id.tv_hr_label,
                           ModelInferenceManager.Mission.HR, "Heart Rate (HR)");
        setupMissionSpinner(R.id.spinner_bp_sys, R.id.tv_bp_sys_label,
                           ModelInferenceManager.Mission.BP_SYS, "Systolic BP (SYS)");
        setupMissionSpinner(R.id.spinner_bp_dia, R.id.tv_bp_dia_label,
                           ModelInferenceManager.Mission.BP_DIA, "Diastolic BP (DIA)");
        setupMissionSpinner(R.id.spinner_spo2, R.id.tv_spo2_label,
                           ModelInferenceManager.Mission.SPO2, "Blood Oxygen (SpO2)");
        setupMissionSpinner(R.id.spinner_rr, R.id.tv_rr_label,
                           ModelInferenceManager.Mission.RR, "Respiration Rate (RR)");

        // Quick setting buttons
        Button btnAllInception = findViewById(R.id.btn_all_transformer);
        btnAllInception.setOnClickListener(v -> setAllToArchitecture(ModelArchitecture.INCEPTION, "Set all to Inception"));

        Button btnAllTransformer = findViewById(R.id.btn_all_resnet);
        btnAllTransformer.setOnClickListener(v -> setAllToArchitecture(ModelArchitecture.TRANSFORMER, "Set all to Transformer"));

        Button btnAllResnet = findViewById(R.id.btn_all_inception);
        btnAllResnet.setOnClickListener(v -> setAllToArchitecture(ModelArchitecture.RESNET, "Set all to ResNet"));

        // Confirm button
        Button btnConfirm = findViewById(R.id.btn_confirm);
        btnConfirm.setOnClickListener(v -> confirmSelection());

        // Cancel button
        Button btnCancel = findViewById(R.id.btn_cancel);
        btnCancel.setOnClickListener(v -> finish());
    }

    private void setupMissionSpinner(int spinnerId, int labelId,
                                   ModelInferenceManager.Mission mission, String labelText) {
        TextView label = findViewById(labelId);
        label.setText(labelText);

        Spinner spinner = findViewById(spinnerId);

        ModelArchitecture[] options = getArchitectureOptionsForMission(mission);
        ArrayAdapter<ModelArchitecture> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            options
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        // Set current value
        ModelArchitecture currentArch = config.getArchitecture(mission);
        spinner.setSelection(getArchitectureIndex(currentArch, options));

        // Listen for changes
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ModelArchitecture selected = options[position];
                config.setArchitecture(mission, selected);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        missionSpinners.put(mission, spinner);
    }

    private int getArchitectureIndex(ModelArchitecture arch, ModelArchitecture[] options) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == arch) return i;
        }
        return 0;
    }

    private ModelArchitecture[] getArchitectureOptionsForMission(ModelInferenceManager.Mission mission) {
        java.util.List<ModelArchitecture> available = new java.util.ArrayList<>();
        for (ModelArchitecture arch : ModelArchitecture.values()) {
            if (!arch.isClassicAlgorithm()) {
                available.add(arch);
                continue;
            }
            switch (arch.getClassicType()) {
                case HR_PEAK:
                case HR_FFT:
                    if (mission == ModelInferenceManager.Mission.HR) {
                        available.add(arch);
                    }
                    break;
                case RR_FFT:
                case RR_PEAK:
                    if (mission == ModelInferenceManager.Mission.RR) {
                        available.add(arch);
                    }
                    break;
                default:
                    break;
            }
        }
        return available.toArray(new ModelArchitecture[0]);
    }

    private void setAllToArchitecture(ModelArchitecture architecture, String toastText) {
        config.setAllArchitectures(architecture);
        refreshSpinnersFromConfig();
        Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show();
    }

    private void refreshSpinnersFromConfig() {
        for (Map.Entry<ModelInferenceManager.Mission, Spinner> entry : missionSpinners.entrySet()) {
            Spinner spinner = entry.getValue();
            ModelArchitecture selectedArch = config.getArchitecture(entry.getKey());
            ModelArchitecture[] options = getArchitectureOptionsForMission(entry.getKey());
            int index = getArchitectureIndex(selectedArch, options);
            spinner.setSelection(index);
        }
    }

    private void confirmSelection() {
        // Return configuration to caller
        Intent result = new Intent();
        result.putExtra(EXTRA_CONFIG, config);
        setResult(Activity.RESULT_OK, result);
        finish();

        Toast.makeText(this, "Model configuration saved", Toast.LENGTH_SHORT).show();
    }
}
