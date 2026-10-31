/*
 * Copyright (C) 2025 dic1911
 *
 * SPDX-License-Identifier: GPL-3.0-or-later OR MIT
 *
 * This file is part of the public IPC interface of Offline Translator and is
 * dual-licensed so that independently licensed apps can bind to the service.
 * You may use it under the terms of the GNU General Public License, version 3
 * or (at your option) any later version, or under the MIT License below.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.davidv.translator;

import dev.davidv.translator.ITranslationCallback;
import dev.davidv.translator.IImageTranslationCallback;

interface ITranslationService {
    void translate(String textToTranslate, String fromLanguage, String toLanguage, ITranslationCallback callback);
    void translateImage(in ParcelFileDescriptor image, String fromLanguage, String toLanguage, IImageTranslationCallback callback);
}