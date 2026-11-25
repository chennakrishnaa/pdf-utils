package app.venkat.offkit.core

import app.venkat.offkit.ui.components.JobUiState

/** Test shorthands for the phases of [JobUiState.Done]. */

/** A result that is neither being saved nor saved yet. */
val JobUiState.isReadyToSave: Boolean get() = this is JobUiState.Done && !saving && savedUri == null

/** A result that was saved at least once and is not being saved now. */
val JobUiState.isSaved: Boolean get() = this is JobUiState.Done && !saving && savedUri != null

/** A save in progress. */
val JobUiState.isSavingNow: Boolean get() = this is JobUiState.Done && saving
