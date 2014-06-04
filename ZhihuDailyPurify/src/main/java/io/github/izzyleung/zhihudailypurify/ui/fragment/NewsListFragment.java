package io.github.izzyleung.zhihudailypurify.ui.fragment;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ListView;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.assist.PauseOnScrollListener;
import de.keyboardsurfer.android.widget.crouton.Crouton;
import de.keyboardsurfer.android.widget.crouton.Style;
import io.github.izzyleung.zhihudailypurify.R;
import io.github.izzyleung.zhihudailypurify.ZhihuDailyPurifyApplication;
import io.github.izzyleung.zhihudailypurify.bean.DailyNews;
import io.github.izzyleung.zhihudailypurify.support.lib.MyAsyncTask;
import io.github.izzyleung.zhihudailypurify.support.util.URLUtils;
import io.github.izzyleung.zhihudailypurify.task.BaseDownloadTask;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import uk.co.senab.actionbarpulltorefresh.library.ActionBarPullToRefresh;
import uk.co.senab.actionbarpulltorefresh.library.PullToRefreshLayout;
import uk.co.senab.actionbarpulltorefresh.library.listeners.OnRefreshListener;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

public class NewsListFragment extends BaseNewsFragment implements OnRefreshListener {
    private String date;

    private boolean isAutoRefresh;
    private boolean isFirstPage;

    // Fragment is single in PortalActivity
    private boolean isSingle;
    private boolean isRefreshed = false;
    private boolean isCached = false;
    private boolean isRecovered = false;

    private ListView listView;
    private PullToRefreshLayout mPullToRefreshLayout;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            Bundle bundle = getArguments();
            date = bundle.getString("date");
            isFirstPage = bundle.getBoolean("first_page?");
            isSingle = bundle.getBoolean("single?");

            if (!isSingle) {
                setRetainInstance(true);
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_news_list, container, false);
        assert view != null;
        listView = (ListView) view.findViewById(R.id.news_list);
        listView.setAdapter(listAdapter);
        listView.setOnScrollListener(new PauseOnScrollListener(ImageLoader.getInstance(), false, true, onScrollListener));

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                listItemOnClick(position);
            }
        });
        listView.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                return listItemOnLongClick(position);
            }
        });


        mPullToRefreshLayout = (PullToRefreshLayout) view.findViewById(R.id.ptr_layout);
        ActionBarPullToRefresh.from(getActivity())
                .allChildrenArePullable()
                .listener(this)
                .setup(mPullToRefreshLayout);

        if (!isRecovered) {
            new RecoverNewsListTask().
                    executeOnExecutor(MyAsyncTask.THREAD_POOL_EXECUTOR);
        }

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        clearActionMode();

        SharedPreferences pref = PreferenceManager.
                getDefaultSharedPreferences(getActivity());
        isAutoRefresh = pref.getBoolean("auto_refresh?", true);
        boolean isShowcase = pref.getBoolean("show_showcase?", true);

//        if (isFirstPage || isSingle) {
        if (isSingle) {
            if (isAutoRefresh && !isRefreshed) {
                if (!isShowcase) {
                    refresh();
                }
            }
        }
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        if (isVisibleToUser) {
            if (isAutoRefresh && !isRefreshed) {
                refresh();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Crouton.cancelAllCroutons();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mPullToRefreshLayout.getHeaderTransformer().
                onConfigurationChanged(getActivity(), newConfig);
    }

    @Override
    public void onRefreshStarted(View view) {
        refresh();
    }

    @Override
    protected boolean isCleanListChoice() {
        int position = listView.getCheckedItemPosition();
        return listView.getFirstVisiblePosition() > position || listView.getLastVisiblePosition() < position;
    }

    @Override
    protected void clearListChoice() {
        listView.clearChoices();
        listAdapter.notifyDataSetChanged();
    }

    @Override
    protected void checkItemAtPosition(int position) {
        listView.setItemChecked(position, true);
    }

    public void refresh() {
        if (isFirstPage) {
            new OriginalGetNewsTask().execute();
        } else {
            if (getActivity() != null) {
                SharedPreferences sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(getActivity());

                if (sharedPreferences.getBoolean("using_accelerate_server?", false)) {
                    if (Integer.parseInt(sharedPreferences.getString("which_accelerate_server", "1")) == 1) {
                        new AccelerateGetNewsTask().execute(SERVERS.SAE);
                    } else {
                        new AccelerateGetNewsTask().execute(SERVERS.HEROKU);
                    }
                } else {
                    new OriginalGetNewsTask().execute();
                }
            }
        }
    }

    private enum SERVERS {SAE, HEROKU}

    private class RecoverNewsListTask extends MyAsyncTask<Void, Void, List<DailyNews>> {

        @Override
        protected List<DailyNews> doInBackground(Void... params) {
            return ZhihuDailyPurifyApplication.getInstance().getDataSource().getDailyNewsList(date);
        }

        @Override
        protected void onPostExecute(List<DailyNews> newsListRecovered) {
            isRecovered = true;
            if (newsListRecovered != null) {
                isCached = true;
                newsList = newsListRecovered;
                listAdapter.setNewsList(newsListRecovered);
            }

            if (isFirstPage) {
                refresh();
            }
        }
    }

    private class SaveNewsListTask extends MyAsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            saveNewsList(newsList);
            return null;
        }

        private void saveNewsList(List<DailyNews> newsList) {
            Type listType = new TypeToken<List<DailyNews>>() {

            }.getType();

            String beanListToJson = new GsonBuilder()
                    .create().toJson(newsList, listType);
            if (isCached) {
                ZhihuDailyPurifyApplication.getInstance()
                        .getDataSource().updateNewsList(date, beanListToJson);
            } else {
                ZhihuDailyPurifyApplication.getInstance()
                        .getDataSource().createDailyNewsList(date, beanListToJson);
            }
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            isCached = true;
        }
    }

    private abstract class BaseGetNewsTask<Params, Progress, Result> extends BaseDownloadTask<Params, Progress, Result> {
        protected boolean isRefreshSuccess = true;
        protected boolean isTheSameContent = true;

        @Override
        protected void onPreExecute() {
            mPullToRefreshLayout.setRefreshing(true);
        }

        @Override
        protected void onPostExecute(Result result) {
            if (isRefreshSuccess && !isTheSameContent) {
                new SaveNewsListTask().execute();
            }

            mPullToRefreshLayout.setRefreshComplete();
            isRefreshed = true;
        }

        protected void warning() {
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Crouton.makeText(getActivity(),
                                getActivity().getString(R.string.network_error),
                                Style.ALERT).show();

                    }
                });
            }
        }
    }

    private class OriginalGetNewsTask extends BaseGetNewsTask<Void, DailyNews, Void> {
        private int position = 0;

        @Override
        protected Void doInBackground(Void... voids) {
            try {
                JSONObject contents = new JSONObject(
                        downloadStringFromUrl(URLUtils.ZHIHU_DAILY_BEFORE_URL + date));

                if (isFirstPage) {
                    checkDate(getActivity(), contents.getString("date"));
                }

                JSONArray newsArray = contents.getJSONArray("stories");
                for (int i = 0; i < newsArray.length(); i++) {
                    JSONObject singleNews = newsArray.getJSONObject(i);

                    DailyNews dailyNews = new DailyNews();
                    dailyNews.setThumbnailUrl(singleNews.has("images")
                            ? (String) singleNews.getJSONArray("images").get(0)
                            : null);
                    dailyNews.setDailyTitle(singleNews.getString("title"));

                    if (!newsList.contains(dailyNews)) {
                        String newsInfoJson = downloadStringFromUrl(URLUtils.ZHIHU_DAILY_OFFLINE_NEWS_URL
                                + singleNews.getString("id"));
                        JSONObject newsDetail = new JSONObject(newsInfoJson);
                        if (newsDetail.has("body")) {
                            Document doc = Jsoup.parse(newsDetail.getString("body"));
                            boolean shouldPublish = updateDailyNews(doc, singleNews.getString("title"), dailyNews);

                            if (shouldPublish) {
                                isTheSameContent = false;
                                publishProgress(dailyNews);
                            }
                        }
                    }
                }
            } catch (JSONException e) {
                isRefreshSuccess = false;
                warning();
            } catch (IOException e) {
                isRefreshSuccess = false;
                warning();
            }

            return null;
        }

        @Override
        protected void onProgressUpdate(DailyNews... values) {
            if (!isCached) {
                newsList.add(values[0]);
            } else {
                newsList.add(position++, values[0]);
            }
            listAdapter.notifyDataSetChanged();
        }

        private void checkDate(Activity activity, String dateString) {
            if (activity != null) {
                SharedPreferences sharedPreferences = PreferenceManager.
                        getDefaultSharedPreferences(activity);
                String cachedDateString = sharedPreferences.getString("date", null);
                boolean isSameDay = cachedDateString == null
                        || dateString.equals(cachedDateString);

                if (!isSameDay) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            newsList.clear();
                            listAdapter.notifyDataSetChanged();
                        }
                    });
                }

                sharedPreferences.edit().putString("date", dateString).commit();
            }
        }

        private boolean updateDailyNews(
                Document doc,
                String dailyTitle,
                DailyNews dailyNews) throws JSONException {
            Elements viewMoreElements = doc.getElementsByClass("view-more");

            if (viewMoreElements.size() > 1) {
                dailyNews.setMulti(true);
                Elements questionTitleElements =
                        doc.getElementsByClass("question-title");

                for (int j = 0; j < viewMoreElements.size(); j++) {
                    if (questionTitleElements.get(j).text().length() == 0) {
                        dailyNews.addQuestionTitle(dailyTitle);
                    } else {
                        dailyNews.addQuestionTitle(questionTitleElements.get(j).text());
                    }

                    Elements viewQuestionElement = viewMoreElements.get(j).
                            select("a");

                    if (viewQuestionElement.text().equals("查看知乎讨论")) {
                        dailyNews.addQuestionUrl(viewQuestionElement.attr("href"));
                    } else {
                        return false;
                    }
                }
            } else if (viewMoreElements.size() == 1) {
                dailyNews.setMulti(false);

                Elements viewQuestionElement = viewMoreElements.select("a");
                if (viewQuestionElement.text().equals("查看知乎讨论")) {
                    dailyNews.setQuestionUrl(viewQuestionElement.attr("href"));
                } else {
                    return false;
                }

                //Question title is the same with daily title
                if (doc.getElementsByClass("question-title").text().length() == 0) {
                    dailyNews.setQuestionTitle(dailyTitle);
                } else {
                    dailyNews.setQuestionTitle(doc.
                            getElementsByClass("question-title").text());
                }
            } else {
                return false;
            }

            return true;
        }
    }

    private class AccelerateGetNewsTask extends BaseGetNewsTask<SERVERS, DailyNews, Void> {
        private List<DailyNews> tempNewsList;

        @Override
        protected Void doInBackground(SERVERS... serverTypes) {
            Type listType = new TypeToken<List<DailyNews>>() {

            }.getType();

            String jsonFromWeb;
            try {
                if (serverTypes[0] == SERVERS.SAE) {
                    jsonFromWeb = downloadStringFromUrl(URLUtils.
                            ZHIHU_DAILY_PURIFY_SAE_BEFORE_URL + date);
                } else {
                    jsonFromWeb = downloadStringFromUrl(URLUtils.
                            ZHIHU_DAILY_PURIFY_HEROKU_BEFORE_URL + date);
                }
            } catch (IOException e) {
                isRefreshSuccess = false;
                warning();
                return null;
            }

            String newsListJSON = Html.fromHtml(
                    Html.fromHtml(jsonFromWeb).toString()).toString();

            if (!TextUtils.isEmpty(newsListJSON)) {
                try {
                    tempNewsList = new GsonBuilder().create().
                            fromJson(newsListJSON, listType);
                } catch (JsonSyntaxException e) {
                    isRefreshSuccess = false;
                    warning();
                }
            } else {
                isRefreshSuccess = false;
                warning();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if (isRefreshSuccess && !newsList.equals(tempNewsList)) {
                isTheSameContent = false;
                newsList = tempNewsList;
                if (getActivity() != null && isAdded()) {
                    listAdapter.setNewsList(newsList);
                    listAdapter.notifyDataSetChanged();
                }
            }

            super.onPostExecute(aVoid);
        }
    }
}