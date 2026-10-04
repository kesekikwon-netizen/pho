package kr.archsurvey.engine

import android.net.Uri

/** 시스템 문서 선택기(파일로 저장 / 파일 열기)를 Activity 밖에서 쓰기 위한 다리. 콜백은 ViewModel 수명이라 화면이 다시 만들어져도 유지된다. */
class DocBridge {
    interface Launcher { fun create(suggestedName: String); fun open(); fun openMany() }
    var launcher: Launcher? = null
    private var createCb: ((Uri?) -> Unit)? = null
    private var openCb: ((Uri?) -> Unit)? = null
    private var openManyCb: ((List<Uri>) -> Unit)? = null

    fun create(suggestedName: String, cb: (Uri?) -> Unit) { val l = launcher ?: return cb(null); createCb = cb; l.create(suggestedName) }
    fun open(cb: (Uri?) -> Unit) { val l = launcher ?: return cb(null); openCb = cb; l.open() }
    fun openMany(cb: (List<Uri>) -> Unit) { val l = launcher ?: return cb(emptyList()); openManyCb = cb; l.openMany() }
    fun deliverOpenMany(uris: List<Uri>) { val c = openManyCb; openManyCb = null; c?.invoke(uris) }
    fun deliverCreate(uri: Uri?) { val c = createCb; createCb = null; c?.invoke(uri) }
    fun deliverOpen(uri: Uri?) { val c = openCb; openCb = null; c?.invoke(uri) }
}
