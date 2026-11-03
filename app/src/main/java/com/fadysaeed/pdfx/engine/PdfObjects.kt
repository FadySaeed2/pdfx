package com.fadysaeed.pdfx.engine

import com.artifex.mupdf.fitz.PDFObject

/**
 * Adds [item] to the end of this array. Use this, never `push(PDFObject)`: in MuPDF 1.28's Java
 * bindings push lets go of the item after adding it although the Java object still owns it, so once
 * the garbage collector finalises that Java object the array points at freed memory (found when a
 * page's contents turned into garbage only on some runs). put(size, item) keeps it properly.
 */
fun PDFObject.append(item: PDFObject) = put(size(), item)
