/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.davidv.translator

data class Rect(
  var left: Int,
  var top: Int,
  var right: Int,
  var bottom: Int,
) {
  constructor(other: Rect) : this(other.left, other.top, other.right, other.bottom)

  fun width(): Int = right - left

  fun height(): Int = bottom - top

  fun centerY(): Int = (top + bottom) / 2

  fun isEmpty(): Boolean = left >= right || top >= bottom

  fun union(other: Rect) {
    if (isEmpty()) {
      left = other.left
      top = other.top
      right = other.right
      bottom = other.bottom
    } else {
      if (other.left < left) left = other.left
      if (other.top < top) top = other.top
      if (other.right > right) right = other.right
      if (other.bottom > bottom) bottom = other.bottom
    }
  }
}
