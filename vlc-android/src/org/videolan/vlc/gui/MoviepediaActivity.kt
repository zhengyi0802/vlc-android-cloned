/*
 * ************************************************************************
 *  NextActivity.kt
 * *************************************************************************
 * Copyright © 2019 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
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
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.gui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.GridLayoutManager
import org.videolan.medialibrary.interfaces.media.AbstractMediaWrapper
import org.videolan.vlc.R
import org.videolan.vlc.databinding.MoviepediaActivityBinding
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.applyTheme
import org.videolan.vlc.moviepedia.models.identify.Media
import org.videolan.vlc.moviepedia.models.identify.getAllResults
import org.videolan.vlc.viewmodels.MoviepediaModel

open class MoviepediaActivity : BaseActivity(), TextWatcher, TextView.OnEditorActionListener {

    private lateinit var moviepediaResultAdapter: MoviepediaResultAdapter

    private lateinit var viewModel: MoviepediaModel
    private lateinit var media: AbstractMediaWrapper
    private lateinit var binding: MoviepediaActivityBinding
    private val clickHandler = ClickHandler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        val intent = intent
        binding = DataBindingUtil.setContentView(this, R.layout.moviepedia_activity)
        binding.handler = clickHandler

        moviepediaResultAdapter = MoviepediaResultAdapter(layoutInflater)
        moviepediaResultAdapter.clickHandler = clickHandler
        binding.nextResults.adapter = moviepediaResultAdapter
        binding.nextResults.layoutManager = GridLayoutManager(this, 2)

        media = intent.getParcelableExtra(MEDIA)

        binding.searchEditText.addTextChangedListener(this)
        binding.searchEditText.setOnEditorActionListener(this)
        viewModel = ViewModelProviders.of(this).get(media.uri.path
                ?: "", MoviepediaModel::class.java)
        viewModel.apiResult.observe(this, Observer {
            moviepediaResultAdapter.setItems(it.getAllResults())
        })
        viewModel.search(media.uri)
        binding.searchEditText.setText(media.title)
    }

    private fun performSearh(query: String) {
        viewModel.search(query)
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

    override fun afterTextChanged(s: Editable?) {}

    override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent): Boolean {
        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
            UiTools.setKeyboardVisibility(binding.root, false)
            performSearh(v.text.toString())
            return true
        }
        return false
    }

    inner class ClickHandler {

        fun onBack(v: View) {
            finish()
        }

        fun onItemClick(item: Media) {
            //todo
            finish()
        }
    }

    companion object {

        const val MEDIA: String = "media"
        const val TAG = "VLC/SearchActivity"
    }
}
