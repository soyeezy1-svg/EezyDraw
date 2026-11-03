package com.eezydraw.core.model

import kotlinx.serialization.Serializable

// IDs are opaque, stable strings. Creation/import policy belongs to the application layer.
@Serializable @JvmInline value class ProjectId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class ElementId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class LevelId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class LayerId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class SystemId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class LibraryItemId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class MaterialId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class ViewId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class IssueId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class RevisionId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class GroupId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class SheetId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class AssetId(val value: String) { init { require(value.isNotBlank()) } }
