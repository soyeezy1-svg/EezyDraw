package com.eezydraw.ui.projects

import androidx.annotation.StringRes
import com.eezydraw.R
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.ProjectTemplate

@StringRes
fun lengthUnitLabel(unit: LengthUnit): Int = when (unit) {
    LengthUnit.MILLIMETRES -> R.string.project_unit_millimetres
    LengthUnit.CENTIMETRES -> R.string.project_unit_centimetres
    LengthUnit.METRES -> R.string.project_unit_metres
    LengthUnit.INCHES -> R.string.project_unit_inches
    LengthUnit.FEET -> R.string.project_unit_feet
}

@StringRes
fun projectTemplateLabel(template: ProjectTemplate): Int = when (template) {
    ProjectTemplate.EMPTY -> R.string.project_template_empty
    ProjectTemplate.OFFICE -> R.string.project_template_office
    ProjectTemplate.RESIDENTIAL -> R.string.project_template_residential
    ProjectTemplate.DATA_CENTER -> R.string.project_template_data_center
}

@StringRes
internal fun projectTemplateDescription(template: ProjectTemplate): Int = when (template) {
    ProjectTemplate.EMPTY -> R.string.project_template_empty_description
    ProjectTemplate.OFFICE -> R.string.project_template_office_description
    ProjectTemplate.RESIDENTIAL -> R.string.project_template_residential_description
    ProjectTemplate.DATA_CENTER -> R.string.project_template_data_center_description
}
