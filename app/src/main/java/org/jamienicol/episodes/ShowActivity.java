/*
 * Copyright (C) 2012-2015 Jamie Nicol <jamie@thenicols.net>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jamienicol.episodes;

import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import com.astuetz.PagerSlidingTabStrip;
import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import org.jamienicol.episodes.db.EpisodesTable;
import org.jamienicol.episodes.db.ShowsProvider;
import org.jamienicol.episodes.db.ShowsTable;
import org.jamienicol.episodes.services.RefreshShowService;
import org.jamienicol.episodes.widget.ObservableScrollView;

public class ShowActivity
	extends ActionBarActivity
	implements LoaderManager.LoaderCallbacks<Cursor>,
	           ViewTreeObserver.OnGlobalLayoutListener,
	           ObservableScrollView.OnScrollChangedListener,
	           ViewPager.OnPageChangeListener,
	           SeasonsListFragment.OnSeasonSelectedListener
{
	private static final String KEY_DEFAULT_TAB = "default_tab";

	private int showId;
	private boolean isShowStarred;

	private ObservableScrollView scrollView;
	private View toolbarContainer;
	private Toolbar toolbar;
	private ImageView headerImage;
	private PagerSlidingTabStrip tabStrip;
	private PagerAdapter pagerAdapter;
	private ViewPager pager;

	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.show_activity);

		final Intent intent = getIntent();
		showId = intent.getIntExtra("showId", -1);
		if (showId == -1) {
			throw new IllegalArgumentException("must provide valid showId");
		}

		final Bundle loaderArgs = new Bundle();
		loaderArgs.putInt("showId", showId);
		getSupportLoaderManager().initLoader(0, loaderArgs, this);

		scrollView = (ObservableScrollView)findViewById(R.id.scroll_view);
		scrollView.setOnScrollChangedListener(this);

		toolbarContainer = findViewById(R.id.toolbar_container);

		toolbar = (Toolbar)findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		headerImage = (ImageView)findViewById(R.id.header_image);

		pagerAdapter =
			new PagerAdapter(this, getSupportFragmentManager(), showId);

		pager = (ViewPager)findViewById(R.id.pager);
		pager.setAdapter(pagerAdapter);

		tabStrip = (PagerSlidingTabStrip)findViewById(R.id.tab_strip);
		tabStrip.setViewPager(pager);
		tabStrip.setOnPageChangeListener(this);

		// Set the default tab from preferences.
		final SharedPreferences prefs =
				PreferenceManager.getDefaultSharedPreferences(this);
		pager.setCurrentItem(prefs.getInt(KEY_DEFAULT_TAB, 0));

		final ViewTreeObserver vto = scrollView.getViewTreeObserver();
		if (vto.isAlive()) {
			vto.addOnGlobalLayoutListener(this);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		final ViewTreeObserver vto = scrollView.getViewTreeObserver();
		if (vto.isAlive()) {
			vto.removeGlobalOnLayoutListener(this);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		final MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.show_activity, menu);

		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {

		final MenuItem toggleStarred =
			menu.findItem(R.id.menu_toggle_show_starred);
		if (isShowStarred) {
			toggleStarred.setIcon(R.drawable.ic_show_starred);
			toggleStarred.setTitle(R.string.menu_unstar_show);
		} else {
			toggleStarred.setIcon(R.drawable.ic_show_unstarred);
			toggleStarred.setTitle(R.string.menu_star_show);
		}

		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case android.R.id.home:
			finish();
			return true;

		case R.id.menu_toggle_show_starred:
			toggleShowStarred();
			return true;

		case R.id.menu_refresh_show:
			refreshShow();
			return true;

		case R.id.menu_mark_show_watched:
			markShowWatched(true);
			return true;

		case R.id.menu_mark_show_not_watched:
			markShowWatched(false);
			return true;

		case R.id.menu_delete_show:
			deleteShow();
			finish();
			return true;

		default:
			return super.onOptionsItemSelected(item);
		}
	}

	/* LoaderManager.LoaderCallbacks<Cursor> */
	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle args) {
		final int showId = args.getInt("showId");
		final Uri uri = Uri.withAppendedPath(ShowsProvider.CONTENT_URI_SHOWS,
		                                     String.valueOf(showId));
		final String[] projection = {
			ShowsTable.COLUMN_NAME,
			ShowsTable.COLUMN_STARRED,
			ShowsTable.COLUMN_FANART_PATH
		};
		return new CursorLoader(this,
		                        uri,
		                        projection,
		                        null,
		                        null,
		                        null);
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
		if (data != null && data.moveToFirst()) {

			// make activity title the show name
			final int nameColumnIndex =
				data.getColumnIndexOrThrow(ShowsTable.COLUMN_NAME);
			toolbar.setTitle(data.getString(nameColumnIndex));

			// maybe update the state of the toggle starred menu item
			final int starredColumnIndex =
				data.getColumnIndexOrThrow(ShowsTable.COLUMN_STARRED);
			final boolean starred =
				data.getInt(starredColumnIndex) > 0 ? true : false;
			if (isShowStarred != starred) {
				isShowStarred = starred;
				// toggle starred menu item needs updated
				supportInvalidateOptionsMenu();
			}

			final int fanartPathColumnIndex =
				data.getColumnIndexOrThrow(ShowsTable.COLUMN_FANART_PATH);
			final String fanartPath = data.getString(fanartPathColumnIndex);
			if (fanartPath != null && !fanartPath.equals("")) {
				final String fanartUrl =
					String.format("http://thetvdb.com/banners/%s", fanartPath);

				final DisplayImageOptions options =
					new DisplayImageOptions.Builder()
					.cacheInMemory(true)
					.cacheOnDisk(true)
					.build();
				ImageLoader.getInstance().displayImage(fanartUrl,
				                                       headerImage,
				                                       options);
			}
		}
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {
		onLoadFinished(loader, null);
	}

	/* ViewTreeObserver.OnGlobalLayoutListener */
	@Override
	public void onGlobalLayout() {
		setDefaultPositions();
		setScrollTranslations();
	}

	/* ObservableScrollView.OnScrollChangedListener */
	@Override
	public void onScrollChanged(int l, int t, int oldl, int oldt) {
		setScrollTranslations();
	}

	/* ViewPager.OnPageChangeListener */
	@Override
	public void onPageScrolled(int position,
	                           float positionOffset,
	                           int positionOffsetPixels) {
	}

	@Override
	public void onPageSelected(int position) {
		final SharedPreferences prefs =
			PreferenceManager.getDefaultSharedPreferences(this);
		final SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(KEY_DEFAULT_TAB, position);
		editor.apply();

		pager.requestLayout();
	}

	@Override
	public void onPageScrollStateChanged(int state) {
	}

	/* SeasonsListFragment.OnSeasonSelectedListener */
	@Override
	public void onSeasonSelected(int seasonNumber) {
		final Intent intent = new Intent(this, SeasonActivity.class);
		intent.putExtra("showId", showId);
		intent.putExtra("seasonNumber", seasonNumber);
		startActivity(intent);
	}

	private void setDefaultPositions() {
		// place the tabStrip below the headerImage
		final ViewGroup.MarginLayoutParams tabStripParams =
			(ViewGroup.MarginLayoutParams)tabStrip.getLayoutParams();
		final int tabStripTop = headerImage.getHeight();
		// only call requestLayout() if it has changed
		if (tabStripParams.topMargin != tabStripTop) {
			tabStripParams.topMargin = tabStripTop;
			tabStrip.requestLayout();
		}

		// place the pager below the tabStrip
		final ViewGroup.MarginLayoutParams pagerParams =
			(ViewGroup.MarginLayoutParams)pager.getLayoutParams();
		final int pagerTop = headerImage.getHeight() + tabStrip.getHeight();
		// only call requestLayout() if it has changed
		if (pagerParams.topMargin != pagerTop) {
			pagerParams.topMargin = pagerTop;
			pager.requestLayout();
		}

		// give the pager a minimum height so that the header image
		// can be completely scrolled off of the screen even if the
		// contents of the pager is small.
		final int minHeight =
			scrollView.getHeight() - toolbar.getHeight() - tabStrip.getHeight();
		if (pager.getMinimumHeight() != minHeight) {
			pager.setMinimumHeight(minHeight);
		}
	}

	private void setScrollTranslations() {
		final int scrollY = scrollView.getScrollY();

		// lock toolbar to top of screen
		toolbarContainer.setTranslationY(scrollY);

		// scroll the header image off of the screen at half the speed
		// everything else scrolls at, creating a parallax effect.
		headerImage.setTranslationY(scrollY / 2);

		// scroll the tab strip until it reaches the toolbar
		tabStrip.setTranslationY(Math.max(0,
		                                  scrollY -
		                                  headerImage.getHeight() +
		                                  toolbar.getHeight()));

		// make toolbar transparent until the tab strip reaches it,
		// and then opaque from that point onwards
		if (scrollY >= headerImage.getHeight() - toolbar.getHeight()) {
			toolbar.setBackgroundColor(getResources().
			                           getColor(R.color.primary));
			toolbar.setTitleTextColor(getResources().
			                          getColor(android.R.color.white));
		} else {
			toolbar.setBackgroundColor(getResources().
			                           getColor(android.R.color.transparent));
			toolbar.setTitleTextColor(getResources().
			                          getColor(android.R.color.transparent));
		}
	}

	private void toggleShowStarred() {
		final ContentResolver contentResolver = getContentResolver();
		final AsyncQueryHandler handler =
			new AsyncQueryHandler(contentResolver) {};
		final ContentValues values = new ContentValues();
		values.put(ShowsTable.COLUMN_STARRED, !isShowStarred);
		final String selection = String.format("%s=?", ShowsTable.COLUMN_ID);
		final String[] selectionArgs = {
			String.valueOf(showId)
		};

		handler.startUpdate(0,
		                    null,
		                    ShowsProvider.CONTENT_URI_SHOWS,
		                    values,
		                    selection,
		                    selectionArgs);
	}

	private void refreshShow() {
		final Intent intent = new Intent(this, RefreshShowService.class);
		intent.putExtra("showId", showId);

		startService(intent);
	}

	private void markShowWatched(boolean watched) {
		final ContentResolver contentResolver = getContentResolver();
		final AsyncQueryHandler handler =
			new AsyncQueryHandler(contentResolver) {};
		final ContentValues epValues = new ContentValues();
		epValues.put(EpisodesTable.COLUMN_WATCHED, watched);
		final String selection =
			String.format("%s=? AND %s!=?",
			              EpisodesTable.COLUMN_SHOW_ID,
			              EpisodesTable.COLUMN_SEASON_NUMBER);
		final String[] selectionArgs = {
			String.valueOf(showId),
			"0"
		};

		handler.startUpdate(0,
		                    null,
		                    ShowsProvider.CONTENT_URI_EPISODES,
		                    epValues,
		                    selection,
		                    selectionArgs);
	}

	private void deleteShow() {
		final ContentResolver contentResolver = getContentResolver();
		final AsyncQueryHandler handler =
			new AsyncQueryHandler(contentResolver) {};

		/* delete all the show's episodes */
		final String epSelection =
			String.format("%s=?", EpisodesTable.COLUMN_SHOW_ID);
		final String[] epSelectionArgs = {
			String.valueOf(showId)
		};

		handler.startDelete(0,
		                    null,
		                    ShowsProvider.CONTENT_URI_EPISODES,
		                    epSelection,
		                    epSelectionArgs);

		/* delete the show itself */
		final Uri showUri =
			Uri.withAppendedPath(ShowsProvider.CONTENT_URI_SHOWS,
			                     String.valueOf(showId));
		handler.startDelete(0,
		                    null,
		                    showUri,
		                    null,
		                    null);
	}

	private static class PagerAdapter
		extends FragmentPagerAdapter
	{
		private final Context context;
		private final int showId;

		public PagerAdapter(final Context context,
		                    final FragmentManager fragmentManager,
		                    final int showId) {
			super(fragmentManager);

			this.context = context;
			this.showId = showId;
		}

		@Override
		public int getCount() {
			return 2;
		}

		@Override
		public CharSequence getPageTitle(final int position) {
			switch (position) {
			case 0:
				return context.getString(R.string.show_tab_overview);
			case 1:
				return context.getString(R.string.show_tab_episodes);
			default:
				return null;
			}
		}

		@Override
		public Fragment getItem(final int position) {
			switch (position) {
			case 0:
				return ShowDetailsFragment.newInstance(showId);
			case 1:
				return SeasonsListFragment.newInstance(showId);
			default:
				return null;
			}
		}
	}
}
