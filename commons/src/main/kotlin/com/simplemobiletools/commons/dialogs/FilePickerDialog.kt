package com.simplemobiletools.commons.dialogs

import android.os.Environment
import android.os.Parcelable
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.view.KeyEvent
import com.simplemobiletools.commons.R
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.adapters.FilepickerItemsAdapter
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.OTG_PATH
import com.simplemobiletools.commons.helpers.SORT_BY_SIZE
import com.simplemobiletools.commons.models.FileDirItem
import com.simplemobiletools.commons.views.Breadcrumbs
import kotlinx.android.synthetic.main.dialog_filepicker.view.*
import java.io.File
import java.util.*

/**
 * The only filepicker constructor with a couple optional parameters
 *
 * @param activity has to be activity to avoid some Theme.AppCompat issues
 * @param currPath initial path of the dialog, defaults to the external storage
 * @param pickFile toggle used to determine if we are picking a file or a folder
 * @param showHidden toggle for showing hidden items, whose name starts with a dot
 * @param showFAB toggle the displaying of a Floating Action Button for creating new folders
 * @param callback the callback used for returning the selected file/folder
 */
class FilePickerDialog(val activity: BaseSimpleActivity,
                       var currPath: String = Environment.getExternalStorageDirectory().toString(),
                       val pickFile: Boolean = true,
                       val showHidden: Boolean = false,
                       val showFAB: Boolean = false,
                       val callback: (pickedPath: String) -> Unit) : Breadcrumbs.BreadcrumbsListener {

    private var mFirstUpdate = true
    private var mPrevPath = ""
    private var mScrollStates = HashMap<String, Parcelable>()

    private lateinit var mDialog: AlertDialog
    private var mDialogView = activity.layoutInflater.inflate(R.layout.dialog_filepicker, null)

    init {
        if (!File(currPath).exists()) {
            currPath = activity.internalStoragePath
        }

        if (File(currPath).isFile) {
            currPath = File(currPath).parent
        }

        mDialogView.filepicker_breadcrumbs.listener = this
        tryUpdateItems()

        val builder = AlertDialog.Builder(activity)
                .setNegativeButton(R.string.cancel, null)
                .setOnKeyListener({ dialogInterface, i, keyEvent ->
                    if (keyEvent.action == KeyEvent.ACTION_UP && i == KeyEvent.KEYCODE_BACK) {
                        val breadcrumbs = mDialogView.filepicker_breadcrumbs
                        if (breadcrumbs.childCount > 1) {
                            breadcrumbs.removeBreadcrumb()
                            currPath = breadcrumbs.getLastItem().path
                            tryUpdateItems()
                        } else {
                            mDialog.dismiss()
                        }
                    }
                    true
                })

        if (!pickFile)
            builder.setPositiveButton(R.string.ok, null)

        if (showFAB) {
            mDialogView.filepicker_fab.apply {
                beVisible()
                setOnClickListener { createNewFolder() }
            }
        }

        mDialog = builder.create().apply {
            activity.setupDialogStuff(mDialogView, this, getTitle())
        }

        if (!pickFile) {
            mDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                verifyPath()
            }
        }
    }

    private fun getTitle() = if (pickFile) R.string.select_file else R.string.select_folder

    private fun createNewFolder() {
        CreateNewFolderDialog(activity, currPath) {
            callback(it)
            mDialog.dismiss()
        }
    }

    private fun tryUpdateItems() {
        Thread {
            getItems(currPath, activity.baseConfig.sorting and SORT_BY_SIZE != 0) {
                activity.runOnUiThread {
                    updateItems(it)
                }
            }
        }.start()
    }

    private fun updateItems(items: List<FileDirItem>) {
        if (!containsDirectory(items) && !mFirstUpdate && !pickFile && !showFAB) {
            verifyPath()
            return
        }

        val sortedItems = items.sortedWith(compareBy({ !it.isDirectory }, { it.name.toLowerCase() }))

        val adapter = FilepickerItemsAdapter(activity, sortedItems, mDialogView.filepicker_list) {
            if ((it as FileDirItem).isDirectory) {
                currPath = it.path
                tryUpdateItems()
            } else if (pickFile) {
                currPath = it.path
                verifyPath()
            }
        }
        adapter.addVerticalDividers(true)

        val layoutManager = mDialogView.filepicker_list.layoutManager as LinearLayoutManager
        mScrollStates[mPrevPath.trimEnd('/')] = layoutManager.onSaveInstanceState()

        mDialogView.apply {
            filepicker_list.adapter = adapter
            filepicker_breadcrumbs.setBreadcrumb(currPath)
            filepicker_fastscroller.allowBubbleDisplay = context.baseConfig.showInfoBubble
            filepicker_fastscroller.setViews(filepicker_list) {
                filepicker_fastscroller.updateBubbleText(sortedItems.getOrNull(it)?.getBubbleText() ?: "")
            }

            layoutManager.onRestoreInstanceState(mScrollStates[currPath.trimEnd('/')])
            filepicker_list.onGlobalLayout {
                filepicker_fastscroller.setScrollTo(filepicker_list.computeVerticalScrollOffset())
            }
        }

        mFirstUpdate = false
        mPrevPath = currPath
    }

    private fun verifyPath() {
        if (currPath.startsWith(OTG_PATH)) {
            val fileDocument = activity.getSomeDocumentFile(currPath) ?: return
            if ((pickFile && fileDocument.isFile) || (!pickFile && fileDocument.isDirectory)) {
                sendSuccess()
            }
        } else {
            val file = File(currPath)
            if ((pickFile && file.isFile) || (!pickFile && file.isDirectory)) {
                sendSuccess()
            }
        }
    }

    private fun sendSuccess() {
        currPath = if (currPath == OTG_PATH || currPath.length == 1) {
            currPath
        } else {
            currPath.trimEnd('/')
        }
        callback(currPath)
        mDialog.dismiss()
    }

    private fun getItems(path: String, getProperFileSize: Boolean, callback: (List<FileDirItem>) -> Unit) {
        if (path.startsWith(OTG_PATH)) {
            activity.getOTGItems(path, showHidden, getProperFileSize, callback)
        } else {
            getRegularItems(path, getProperFileSize, callback)
        }
    }

    private fun getRegularItems(path: String, getProperFileSize: Boolean, callback: (List<FileDirItem>) -> Unit) {
        val items = ArrayList<FileDirItem>()
        val base = File(path)
        val files = base.listFiles()
        if (files == null) {
            callback(items)
            return
        }

        for (file in files) {
            if (!showHidden && file.isHidden) {
                continue
            }

            val curPath = file.absolutePath
            val curName = curPath.getFilenameFromPath()
            val size = if (getProperFileSize) file.getProperSize(showHidden) else file.length()
            items.add(FileDirItem(curPath, curName, file.isDirectory, getChildren(file), size))
        }
        callback(items)
    }

    private fun getChildren(file: File): Int {
        return if (file.listFiles() == null || !file.isDirectory) {
            0
        } else {
            file.listFiles().filter { !it.isHidden || (it.isHidden && showHidden) }.size
        }
    }

    private fun containsDirectory(items: List<FileDirItem>) = items.any { it.isDirectory }

    override fun breadcrumbClicked(id: Int) {
        if (id == 0) {
            StoragePickerDialog(activity, currPath) {
                currPath = it
                tryUpdateItems()
            }
        } else {
            val item = mDialogView.filepicker_breadcrumbs.getChildAt(id).tag as FileDirItem
            if (currPath != item.path.trimEnd('/')) {
                currPath = item.path
                tryUpdateItems()
            }
        }
    }
}
