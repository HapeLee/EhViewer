/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kazusa.ehviewer.client.data

/**
 * 详情页 / 预览页的缩略图集合。
 *
 * 只保留「描述」类接口：Compose 侧（`GalleryPreviewsScreen`）拿 [getGalleryPreview]
 * 自己拼 Coil 的 `ImageRequest`。旧的 `load(view: LoadImageView, …)`（把图直接塞进
 * `LoadImageView`）随老控件一起删了。
 */
abstract class PreviewSet {
    abstract fun size(): Int
    abstract fun getPosition(index: Int): Int
    abstract fun getPageUrlAt(index: Int): String
    abstract fun getSha1At(index: Int): String
    abstract fun getGalleryPreview(gid: Long, index: Int): GalleryPreview
}
