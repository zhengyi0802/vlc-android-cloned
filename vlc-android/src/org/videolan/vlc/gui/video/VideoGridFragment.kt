/*****************************************************************************
 * VideoGridFragment.kt
 *
 * Copyright © 2019 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 */

package org.videolan.vlc.gui.video

import android.annotation.TargetApi
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagedList
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import org.videolan.medialibrary.interfaces.AbstractMedialibrary
import org.videolan.medialibrary.interfaces.media.AbstractFolder
import org.videolan.medialibrary.interfaces.media.AbstractMediaWrapper
import org.videolan.medialibrary.interfaces.media.AbstractVideoGroup
import org.videolan.medialibrary.media.Folder
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.tools.MultiSelectHelper
import org.videolan.tools.isStarted
import org.videolan.vlc.R
import org.videolan.vlc.databinding.VideoGridBinding
import org.videolan.vlc.gui.ContentActivity
import org.videolan.vlc.gui.MainActivity
import org.videolan.vlc.gui.MoviepediaActivity
import org.videolan.vlc.gui.SecondaryActivity
import org.videolan.vlc.gui.browser.MediaBrowserFragment
import org.videolan.vlc.gui.dialogs.CtxActionReceiver
import org.videolan.vlc.gui.dialogs.SavePlaylistDialog
import org.videolan.vlc.gui.dialogs.showContext
import org.videolan.vlc.gui.helpers.ItemOffsetDecoration
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.view.EmptyLoadingState
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.media.PlaylistManager
import org.videolan.vlc.media.getAll
import org.videolan.vlc.providers.medialibrary.VideosProvider
import org.videolan.vlc.reloadLibrary
import org.videolan.vlc.util.*
import org.videolan.vlc.viewmodels.mobile.VideoGroupingType
import org.videolan.vlc.viewmodels.mobile.VideosViewModel
import org.videolan.vlc.viewmodels.mobile.getViewModel
import java.util.*

private const val TAG = "VLC/VideoListFragment"

private const val SET_REFRESHING = 15
private const val UNSET_REFRESHING = 16

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
class VideoGridFragment : MediaBrowserFragment<VideosViewModel>(), SwipeRefreshLayout.OnRefreshListener, Observer<PagedList<AbstractMediaWrapper>>, CtxActionReceiver {

    private lateinit var videoListAdapter: VideoListAdapter
    private lateinit var multiSelectHelper: MultiSelectHelper<MediaLibraryItem>
    private lateinit var binding: VideoGridBinding
    private var gridItemDecoration: RecyclerView.ItemDecoration? = null

    private fun FragmentActivity.open(item: MediaLibraryItem) {
        val i = Intent(activity, SecondaryActivity::class.java)
        i.putExtra("fragment", SecondaryActivity.VIDEO_GROUP_LIST)
        if (item is AbstractFolder) i.putExtra(KEY_FOLDER, item)
        else if (item is AbstractVideoGroup) i.putExtra(KEY_GROUP, item)
        startActivityForResult(i, SecondaryActivity.ACTIVITY_RESULT_SECONDARY)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!::videoListAdapter.isInitialized) {
            val preferences = Settings.getInstance(requireContext())
            val seenMarkVisible = preferences.getBoolean("media_seen", true)
            videoListAdapter = VideoListAdapter(lifecycleScope, seenMarkVisible, actor)
            multiSelectHelper = videoListAdapter.multiSelectHelper
            val folder = if (savedInstanceState != null) savedInstanceState.getParcelable<AbstractFolder>(KEY_FOLDER)
            else arguments?.getParcelable(KEY_FOLDER)
            val group = if (savedInstanceState != null) savedInstanceState.getParcelable<AbstractVideoGroup>(KEY_GROUP)
            else arguments?.getParcelable(KEY_GROUP)
            val grouping = arguments?.getSerializable(KEY_GROUPING) as VideoGroupingType? ?: VideoGroupingType.NONE
            viewModel = getViewModel(grouping, folder, group)
            setDataObservers()
            AbstractMedialibrary.lastThumb.observe(this, thumbObs)
        }
    }

    private fun setDataObservers() {
        videoListAdapter.dataType = viewModel.groupingType
        when (viewModel.groupingType) {
            VideoGroupingType.NONE -> {
                (viewModel.provider as VideosProvider).pagedList.observe(this, this)
            }
            VideoGroupingType.FOLDER, VideoGroupingType.NAME -> {
                viewModel.provider.pagedList.observe(requireActivity(), Observer {
                    if (it != null) videoListAdapter.submitList(it as PagedList<MediaLibraryItem>)
                    restoreMultiSelectHelper()
                })
            }
        }

        viewModel.provider.loading.observe(this, Observer { loading ->
            setRefreshing(loading)
            if (!loading) {
                setFabPlayVisibility(true)
                menu?.let { UiTools.updateSortTitles(it, viewModel.provider) }
                restoreMultiSelectHelper()
            }
            (activity as? MainActivity)?.refreshing = loading
            updateEmptyView()
        })
        videoListAdapter.showFilename.set(viewModel.groupingType == VideoGroupingType.NONE && viewModel.provider.sort == AbstractMedialibrary.SORT_FILENAME)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.ml_menu_last_playlist).isVisible = true
        menu.findItem(R.id.ml_menu_video_group).isVisible = viewModel.group == null && viewModel.folder == null
        val displayInCards = Settings.getInstance(requireActivity()).getBoolean("video_display_in_cards", true)
        menu.findItem(R.id.ml_menu_display_grid).isVisible = !displayInCards
        menu.findItem(R.id.ml_menu_display_list).isVisible = displayInCards
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.ml_menu_last_playlist -> {
                MediaUtils.loadlastPlaylist(activity, PLAYLIST_TYPE_VIDEO)
                true
            }
            R.id.ml_menu_display_list, R.id.ml_menu_display_grid -> {
                val displayInCards = Settings.getInstance(requireActivity()).getBoolean("video_display_in_cards", true)
                Settings.getInstance(requireActivity()).edit().putBoolean("video_display_in_cards", !displayInCards).apply()
                (activity as ContentActivity).forceLoadVideoFragment()
                true
            }
            R.id.video_min_group_length_disable -> {
                lifecycleScope.launchWhenStarted {
                    withContext(Dispatchers.IO) {
                        Settings.getInstance(requireActivity()).edit().putString("video_min_group_length", "-1").commit()
                    }
                    changeGroupingType(VideoGroupingType.NONE)
                }
                true
            }
            R.id.video_min_group_length_folder -> {
                lifecycleScope.launchWhenStarted {
                    withContext(Dispatchers.IO) {
                        Settings.getInstance(requireActivity()).edit().putString("video_min_group_length", "0").commit()
                    }
                    changeGroupingType(VideoGroupingType.FOLDER)
                }
                true
            }
            R.id.video_min_group_length_name -> {
                lifecycleScope.launchWhenStarted {
                    withContext(Dispatchers.IO) {
                        Settings.getInstance(requireActivity()).edit().putString("video_min_group_length", "6").commit()
                    }
                    changeGroupingType(VideoGroupingType.NAME)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun sortBy(sort: Int) {
        videoListAdapter.showFilename.set(sort == AbstractMedialibrary.SORT_FILENAME)
        super.sortBy(sort)
    }

    private fun changeGroupingType(type: VideoGroupingType) {
        viewModel.provider.pagedList.removeObservers(this)
        viewModel.provider.loading.removeObservers(this)
        viewModel.changeGroupingType(type)
        setDataObservers()
        (activity as? AppCompatActivity)?.run {
            supportActionBar?.title = title
            invalidateOptionsMenu()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = VideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val empty = viewModel.isEmpty()
        binding.emptyLoading.state = if (empty) EmptyLoadingState.LOADING else EmptyLoadingState.NONE
        binding.empty = empty
        binding.emptyLoading.setOnNoMediaClickListener {
            requireActivity().setResult(RESULT_RESTART)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        swipeRefreshLayout.setOnRefreshListener(this)
        binding.videoGrid.adapter = videoListAdapter
    }

    override fun onStart() {
        super.onStart()
        registerForContextMenu(binding.videoGrid)
        updateViewMode()
        setFabPlayVisibility(true)
        fabPlay?.setImageResource(R.drawable.ic_fab_play)
        if (!viewModel.isEmpty() && getFilterQuery() == null) viewModel.refresh()
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.coroutineContext.cancelChildren()
        unregisterForContextMenu(binding.videoGrid)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_FOLDER, viewModel.folder)
        outState.putParcelable(KEY_GROUP, viewModel.group)
        outState.putSerializable(KEY_GROUPING, viewModel.groupingType)
    }

    override fun onDestroy() {
        super.onDestroy()
        videoListAdapter.release()
        gridItemDecoration = null
    }

    override fun onChanged(list: PagedList<AbstractMediaWrapper>?) {
        if (list != null) videoListAdapter.submitList(list as PagedList<MediaLibraryItem>)
    }

    override fun getTitle() = when(viewModel.groupingType) {
        VideoGroupingType.NONE -> viewModel.folder?.displayTitle ?: viewModel.group?.displayTitle ?: getString(R.string.videos)
        VideoGroupingType.FOLDER -> getString(R.string.videos_folders_title)
        VideoGroupingType.NAME -> getString(R.string.videos_groups_title)
    }

    override fun getMultiHelper(): MultiSelectHelper<VideosViewModel>? = if (::videoListAdapter.isInitialized) videoListAdapter.multiSelectHelper as? MultiSelectHelper<VideosViewModel> else null

    private fun updateViewMode() {
        if (view == null || activity == null) {
            Log.w(TAG, "Unable to setup the view")
            return
        }
        val res = resources
        if (gridItemDecoration == null)
            gridItemDecoration = ItemOffsetDecoration(resources, R.dimen.left_right_1610_margin, R.dimen.top_bottom_1610_margin)
        val listMode = !Settings.getInstance(requireContext()).getBoolean("video_display_in_cards", true)

        // Select between grid or list
        binding.videoGrid.removeItemDecoration(gridItemDecoration!!)
        if (!listMode) {
            val thumbnailWidth = res.getDimensionPixelSize(R.dimen.grid_card_thumb_width)
            val margin = binding.videoGrid.paddingStart + binding.videoGrid.paddingEnd
            val columnWidth = binding.videoGrid.getPerfectColumnWidth(thumbnailWidth, margin) - res.getDimensionPixelSize(R.dimen.left_right_1610_margin) * 2
            binding.videoGrid.columnWidth = columnWidth
            videoListAdapter.setGridCardWidth(binding.videoGrid.columnWidth)
            binding.videoGrid.addItemDecoration(gridItemDecoration!!)
        }
        binding.videoGrid.setNumColumns(if (listMode) 1 else -1)
        if (videoListAdapter.isListMode != listMode) videoListAdapter.isListMode = listMode
    }

    override fun onFabPlayClick(view: View) {
        viewModel.playAll(activity)
    }

    private fun updateEmptyView() {
        val empty = viewModel.isEmpty() && videoListAdapter.currentList.isNullOrEmpty()
        val working = mediaLibrary.isWorking
        binding.emptyLoading.state = when {
            empty && working -> EmptyLoadingState.LOADING
            empty && !working -> EmptyLoadingState.EMPTY
            else -> EmptyLoadingState.NONE
        }
        binding.empty = empty && !working
    }

    override fun onRefresh() {
        activity?.reloadLibrary()
    }

    override fun setFabPlayVisibility(enable: Boolean) {
        super.setFabPlayVisibility(!viewModel.isEmpty() && enable)
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        when (viewModel.groupingType) {
            VideoGroupingType.NONE -> mode.menuInflater.inflate(R.menu.action_mode_video, menu)
            VideoGroupingType.FOLDER -> mode.menuInflater.inflate(R.menu.action_mode_folder, menu)
            VideoGroupingType.NAME -> mode.menuInflater.inflate(R.menu.action_mode_video_group, menu)
        }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val count = multiSelectHelper.getSelectionCount()
        if (count == 0) {
            stopActionMode()
            return false
        }
        when (viewModel.groupingType) {
            VideoGroupingType.NONE -> {
                menu.findItem(R.id.action_video_append).isVisible = PlaylistManager.hasMedia()
                menu.findItem(R.id.action_video_info).isVisible = count == 1
            }
            else -> {}
        }
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (!isStarted()) return false
        when (viewModel.groupingType) {
            VideoGroupingType.NONE -> {
                val list = multiSelectHelper.getSelection().map { it as AbstractMediaWrapper }
                if (list.isNotEmpty()) {
                    when (item.itemId) {
                        R.id.action_video_play -> MediaUtils.openList(activity, list, 0)
                        R.id.action_video_append -> MediaUtils.appendMedia(activity, list)
                        R.id.action_video_info -> showInfoDialog(list[0])
                        //            case R.id.action_video_delete:
                        //                for (int position : rowsAdapter.getSelectedPositions())
                        //                    removeVideo(position, rowsAdapter.getItem(position));
                        //                break;
                        R.id.action_video_download_subtitles -> MediaUtils.getSubs(requireActivity(), list)
                        R.id.action_video_play_audio -> {
                            for (media in list) media.addFlags(AbstractMediaWrapper.MEDIA_FORCE_AUDIO)
                            MediaUtils.openList(activity, list, 0)
                        }
                        R.id.action_mode_audio_add_playlist -> UiTools.addToPlaylist(requireActivity(), list)
                        R.id.action_video_delete -> removeItems(list)
                        else -> {
                            stopActionMode()
                            return false
                        }
                    }
                }
            }
            VideoGroupingType.FOLDER -> {
                val selection = ArrayList<AbstractFolder>()
                for (mediaLibraryItem in multiSelectHelper.getSelection()) {
                    selection.add(mediaLibraryItem as Folder)
                }
                when (item.itemId) {
                    R.id.action_folder_play -> viewModel.playFoldersSelection(selection)
                    R.id.action_folder_append -> viewModel.appendFoldersSelection(selection)
                    R.id.action_folder_add_playlist -> lifecycleScope.launch { UiTools.addToPlaylist(requireActivity(), withContext(Dispatchers.Default) { selection.getAll() }) }
                    else -> return false
                }
            }
            VideoGroupingType.NAME -> {
                val selection = multiSelectHelper.getSelection()
                when (item.itemId) {
                    R.id.action_videogroup_play -> MediaUtils.openList(activity, selection.getAll(), 0)
                    R.id.action_videogroup_append -> MediaUtils.appendMedia(activity, selection.getAll())
                    R.id.action_videogroup_add_playlist -> lifecycleScope.launch { UiTools.addToPlaylist(requireActivity(), withContext(Dispatchers.Default) { selection.getAll() }) }
                    else -> return false
                }
            }
        }
        stopActionMode()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        actionMode = null
        setFabPlayVisibility(true)
        multiSelectHelper.clearSelection()
    }

    fun updateSeenMediaMarker() {
        videoListAdapter.setSeenMediaMarkerVisible(Settings.getInstance(requireContext()).getBoolean("media_seen", true))
        videoListAdapter.notifyItemRangeChanged(0, videoListAdapter.itemCount - 1, UPDATE_SEEN)
    }

    override fun onCtxAction(position: Int, option: Int) {
        if (position >= videoListAdapter.itemCount) return
        val activity = activity ?: return
        when (val media = videoListAdapter.getItem(position)) {
            is AbstractMediaWrapper -> when (option) {
                CTX_PLAY_FROM_START -> viewModel.playVideo(activity, media, position,true)
                CTX_PLAY_AS_AUDIO -> viewModel.playAudio(activity, media)
                CTX_PLAY_ALL -> MediaUtils.playAll(activity, viewModel.provider as VideosProvider, position, false)
                CTX_INFORMATION -> showInfoDialog(media)
                CTX_DELETE -> removeItem(media)
                CTX_APPEND -> MediaUtils.appendMedia(activity, media)
                CTX_PLAY_NEXT -> MediaUtils.insertNext(requireActivity(), media.tracks)
                CTX_DOWNLOAD_SUBTITLES -> MediaUtils.getSubs(requireActivity(), media)
                CTX_ADD_TO_PLAYLIST -> UiTools.addToPlaylist(requireActivity(), media.tracks, SavePlaylistDialog.KEY_NEW_TRACKS)
                CTX_FIND_METADATA -> startActivity(Intent(requireActivity(), MoviepediaActivity::class.java).apply { putExtra(MoviepediaActivity.MEDIA, media) })
                CTX_SHARE -> lifecycleScope.launch { (requireActivity() as AppCompatActivity).share(media) }
            }
            is AbstractFolder -> when (option) {
                CTX_PLAY -> lifecycleScope.launch { viewModel.play(position) }
                CTX_APPEND -> lifecycleScope.launch { viewModel.append(position) }
                CTX_ADD_TO_PLAYLIST -> viewModel.addToPlaylist(requireActivity(), position)
            }
            is AbstractVideoGroup -> when (option) {
                CTX_PLAY -> lifecycleScope.launch { viewModel.play(position) }
                CTX_APPEND -> lifecycleScope.launch { viewModel.append(position) }
                CTX_ADD_TO_PLAYLIST -> viewModel.addToPlaylist(requireActivity(), position)
            }
        }
    }

    private val thumbObs = Observer<AbstractMediaWrapper> { media ->
        if (!::videoListAdapter.isInitialized || viewModel.provider !is VideosProvider) return@Observer
        val position = viewModel.provider.pagedList.value?.indexOf(media) ?: return@Observer
        val item = videoListAdapter.getItem(position) as? AbstractMediaWrapper
        item?.run {
            artworkURL = media.artworkURL
            videoListAdapter.notifyItemChanged(position)
        }
    }

    private val actor = lifecycleScope.actor<VideoAction>(capacity = Channel.UNLIMITED) {
        for (action in channel) when (action) {
            is VideoClick -> {
                when (action.item) {
                    is AbstractMediaWrapper -> {
                        if (actionMode != null) {
                            multiSelectHelper.toggleSelection(action.position)
                            invalidateActionMode()
                        } else {
                            viewModel.playVideo(activity, action.item, action.position)
                        }
                    }
                    is AbstractFolder -> {
                        if (actionMode != null) {
                            multiSelectHelper.toggleSelection(action.position)
                            invalidateActionMode()
                        } else activity?.open(action.item)
                    }
                    is AbstractVideoGroup -> when {
                        actionMode != null -> {
                            multiSelectHelper.toggleSelection(action.position)
                            invalidateActionMode()
                        }
                        action.item.mediaCount() == 1 -> viewModel.play(action.position)
                        else -> activity?.open(action.item)
                    }
                }
            }
            is VideoLongClick -> {
                multiSelectHelper.toggleSelection(action.position, true)
                if (actionMode == null) startActionMode()
            }
            is VideoCtxClick -> {
                when (action.item) {
                    is AbstractFolder, is AbstractVideoGroup -> showContext(requireActivity(), this@VideoGridFragment, action.position, action.item.title, CTX_FOLDER_FLAGS)
                    is AbstractMediaWrapper -> {
                        val group = action.item.type == AbstractMediaWrapper.TYPE_GROUP
                        var flags = if (group) CTX_VIDEO_GOUP_FLAGS else CTX_VIDEO_FLAGS
                        if (action.item.time != 0L && !group) flags = flags or CTX_PLAY_FROM_START
                        showContext(requireActivity(), this@VideoGridFragment, action.position, action.item.getTitle(), flags)
                    }
                }
            }
        }
    }
}

sealed class VideoAction
class VideoClick(val position: Int, val item: MediaLibraryItem) : VideoAction()
class VideoLongClick(val position: Int, val item: MediaLibraryItem) : VideoAction()
class VideoCtxClick(val position: Int, val item: MediaLibraryItem) : VideoAction()
