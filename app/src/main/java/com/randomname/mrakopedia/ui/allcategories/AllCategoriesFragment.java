package com.randomname.mrakopedia.ui.allcategories;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.randomname.mrakopedia.MainActivity;
import com.randomname.mrakopedia.MrakopediaApplication;
import com.randomname.mrakopedia.R;
import com.randomname.mrakopedia.api.MrakopediaApiWorker;
import com.randomname.mrakopedia.models.api.allcategories.AllCategoriesResult;
import com.randomname.mrakopedia.models.api.allcategories.Allcategories;
import com.randomname.mrakopedia.models.realm.CategoryRealm;
import com.randomname.mrakopedia.realm.DBWorker;
import com.randomname.mrakopedia.ui.RxBaseFragment;
import com.randomname.mrakopedia.ui.categorymembers.CategoryMembersActivity;
import com.randomname.mrakopedia.ui.search.SearchCallback;
import com.randomname.mrakopedia.utils.NetworkUtils;
import com.randomname.mrakopedia.utils.StringUtils;
import com.randomname.mrakopedia.utils.Utils;

import java.util.ArrayList;

import butterknife.Bind;
import butterknife.ButterKnife;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

/**
 * Created by vgrigoryev on 20.01.2016.
 */
public class AllCategoriesFragment extends RxBaseFragment implements SearchCallback {

    private static final String TAG = "AllCategoriesFragment";
    private static final String RESULT_ARRAY_LIST_KEY = "resultArrayListKey";
    private static final String CONTINUE_STRING_KEY = "continueStringKey";

    @Bind(R.id.all_categories_recycler_view)
    RecyclerView recyclerView;
    @Bind(R.id.error_text_view)
    carbon.widget.TextView errorTextView;

    private AllCategoriesAdapter adapter;
    private ArrayList<Allcategories> resultArrayList;
    private ArrayList<Allcategories> copiedArrayList;

    private String continueString = "";
    private boolean isLoading = false;

    private Tracker mTracker;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            continueString = savedInstanceState.getString(CONTINUE_STRING_KEY);
            resultArrayList = savedInstanceState.getParcelableArrayList(RESULT_ARRAY_LIST_KEY);

            if (resultArrayList != null) {
                copiedArrayList = new ArrayList<>(resultArrayList);
            } else {
                copiedArrayList = new ArrayList<>();
            }

        } else {
            resultArrayList = new ArrayList<>();
            copiedArrayList = new ArrayList<>();
        }

        MrakopediaApplication application = (MrakopediaApplication) getActivity().getApplication();
        mTracker = application.getDefaultTracker();

        setHasOptionsMenu(true);
        ((MainActivity)getActivity()).registerForSearchListener(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.all_categories_fragment, container, false);
        ButterKnife.bind(this, view);

        adapter = new AllCategoriesAdapter(resultArrayList, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int position = recyclerView.getChildAdapterPosition(v) - 1;
                Intent intent = new Intent(getActivity(), CategoryMembersActivity.class);
                intent.putExtra(CategoryMembersActivity.CATEGORY_NAME_EXTRA, adapter.getDisplayedData().get(position).getTitle());
                startActivity(intent);
            }
        });

        LinearLayoutManager manager = new LinearLayoutManager(getActivity());
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(manager);
        recyclerView.addOnScrollListener(((MainActivity) getActivity()).toolbarHideRecyclerOnScrollListener);

        if (resultArrayList.isEmpty()) {
            new android.os.Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    loadCategoryMembersViaNetwork();
                }
            }, 100);

        }

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ((MainActivity)getActivity()).unregisterForSearchListener(this);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_all_categories, menu);

        if(Build.VERSION.SDK_INT < 21) {
            try {
                final ViewTreeObserver viewTreeObserver = getActivity().getWindow().getDecorView().getViewTreeObserver();
                viewTreeObserver.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        View menuButton = getActivity().findViewById(R.id.action_search);
                        // This could be called when the button is not there yet, so we must test for null
                        if (menuButton != null) {

                            Utils.setRippleToMenuItem(menuButton, getActivity());

                            if (Build.VERSION.SDK_INT < 16) {
                                getActivity().getWindow().getDecorView().getViewTreeObserver().removeGlobalOnLayoutListener(this);
                            } else {
                                getActivity().getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_search:
                ((MainActivity)getActivity()).openSearchView();
                return true;
            default:
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onResume() {
        super.onResume();
        mTracker.setScreenName(TAG);
        mTracker.send(new HitBuilders.ScreenViewBuilder().build());
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putParcelableArrayList(RESULT_ARRAY_LIST_KEY, copiedArrayList);
        outState.putString(CONTINUE_STRING_KEY, continueString);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onConnectedToInternet() {
        if (continueString != null && continueString.isEmpty()) {
            copiedArrayList.clear();
            resultArrayList.clear();
            loadCategoryMembersViaNetwork();
        }
    }

    private void loadCategoryMembersViaNetwork() {
        if (continueString == null || isLoading) {
            return;
        }

        isLoading = true;

        Subscription getAllCategoriesSubscription =
                MrakopediaApiWorker
                        .getInstance()
                        .getAllCategories(continueString)
                        .doOnNext(new Action1<AllCategoriesResult>() {
                            @Override
                            public void call(AllCategoriesResult allCategoriesResult) {
                                if (allCategoriesResult.getAccContinue() != null) {
                                    continueString = allCategoriesResult.getAccContinue().getAccontinue();
                                } else {
                                    continueString = null;
                                }
                            }
                        })
                        .flatMap(new Func1<AllCategoriesResult, Observable<Allcategories>>() {
                            @Override
                            public Observable<Allcategories> call(AllCategoriesResult allCategoriesResult) {
                                return Observable.from(allCategoriesResult.getQuery().getAllcategories());
                            }
                        })
                        .filter(new Func1<Allcategories, Boolean>() {
                            @Override
                            public Boolean call(Allcategories allcategories) {
                                for (String banString : Utils.categoriesBanList) {
                                    if (allcategories.getTitle().toLowerCase().contains(banString.toLowerCase())) {
                                        return false;
                                    }
                                }

                                return true;
                            }
                        })
                        .subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new Subscriber<Allcategories>() {
                            @Override
                            public void onCompleted() {
                                adapter.notifyDataSetChanged();
                                isLoading = false;

                                loadCategoryMembersViaNetwork();

                                if (recyclerView.getVisibility() != View.VISIBLE) {
                                    recyclerView.setVisibility(View.VISIBLE);
                                    errorTextView.setVisibility(View.GONE);
                                }
                            }

                            @Override
                            public void onError(Throwable e) {
                                Log.e(TAG, e.toString());
                                e.printStackTrace();

                                isLoading = false;

                                if (adapter.getDisplayedData().size() <= 1) {
                                    loadCategoriesViaRealm();
                                } else {
                                    Toast.makeText(getActivity(), getString(R.string.error_loading_categories) + " " + getString(R.string.no_internet_text), Toast.LENGTH_SHORT).show();
                                }
                            }

                            @Override
                            public void onNext(Allcategories category) {
                                adapter.getDisplayedData().add(category);
                                copiedArrayList.add(category);
                            }
                        });


        bindToLifecycle(getAllCategoriesSubscription);
    }

    private void loadCategoriesViaRealm() {
        Subscription getAllCategoriesSubscription =
                        Observable.just("")
                        .flatMap(new Func1<String, Observable<CategoryRealm>>() {
                            @Override
                            public Observable<CategoryRealm> call(String s) {
                                return DBWorker.getAllCategories();
                            }
                        })
                        .filter(new Func1<CategoryRealm, Boolean>() {
                            @Override
                            public Boolean call(CategoryRealm categoryRealm) {
                                return !categoryRealm.getCategoryMembersTitles().isEmpty();
                            }
                        })
                        .map(new Func1<CategoryRealm, Allcategories>() {
                            @Override
                            public Allcategories call(CategoryRealm categoryRealm) {
                                Allcategories category = new Allcategories();
                                category.setFiles("");
                                category.setPages(String.valueOf(categoryRealm.getCategoryMembersTitles().size()));
                                category.setTitle(categoryRealm.getTitle());
                                category.setSubcats("");
                                category.setSize("");

                                return category;
                            }
                        })
                        .subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new Subscriber<Allcategories>() {
                            @Override
                            public void onCompleted() {
                                if (adapter.getDisplayedData().size() <= 1) {
                                    errorTextView.setVisibility(View.VISIBLE);

                                    if (!NetworkUtils.isInternetAvailable(getActivity())) {
                                        errorTextView.setText(getString(R.string.error_loading_categories) + " " + getString(R.string.no_internet_text));
                                    }
                                    recyclerView.setVisibility(View.GONE);
                                } else {
                                    adapter.notifyDataSetChanged();
                                }
                            }

                            @Override
                            public void onError(Throwable e) {
                                Log.e(TAG, e.toString());
                                e.printStackTrace();


                                if (adapter.getDisplayedData().size() <= 1) {
                                    errorTextView.setVisibility(View.VISIBLE);

                                    if (!NetworkUtils.isInternetAvailable(getActivity())) {
                                        errorTextView.setText(getString(R.string.error_loading_categories) + " " + getString(R.string.no_internet_text));
                                    }
                                    recyclerView.setVisibility(View.GONE);
                                }
                            }

                            @Override
                            public void onNext(Allcategories allcategories) {
                                adapter.getDisplayedData().add(allcategories);
                                copiedArrayList.add(allcategories);
                            }
                        });


        bindToLifecycle(getAllCategoriesSubscription);
    }

    @Override
    public void onSearchChanged(final String search) {
        resultArrayList.clear();
        Subscription subscription =
                Observable.from(copiedArrayList)
                .filter(new Func1<Allcategories, Boolean>() {
                    @Override
                    public Boolean call(Allcategories allcategories) {
                        return allcategories.getTitle().toLowerCase().contains(search.toLowerCase());
                    }
                })
                .subscribe(new Subscriber<Allcategories>() {
                    @Override
                    public void onCompleted() {
                        adapter.notifyDataSetChanged();
                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onNext(Allcategories allcategories) {
                        resultArrayList.add(allcategories);
                    }
                });

        bindToLifecycle(subscription);
    }

    private class AllCategoriesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final static int LIST_ITEM_TYPE = 0;
        private final static int SPACER_ITEM_TYPE = 1;

        private String[] pages = {"страница", "страницы", "страниц"};

        ArrayList<Allcategories> categoriesArrayList;
        View.OnClickListener onClickListener;

        public AllCategoriesAdapter(ArrayList<Allcategories> categoriesArrayList, View.OnClickListener onClickListener) {
            this.categoriesArrayList = categoriesArrayList;
            this.onClickListener = onClickListener;

            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            if (categoriesArrayList.size() <= position) {
                return super.getItemId(position);
            }

            return categoriesArrayList.get(position).getId();
        }

        public ArrayList<Allcategories> getDisplayedData() {
            return categoriesArrayList;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return SPACER_ITEM_TYPE;
            } else {
                return LIST_ITEM_TYPE;
            }
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;

            if (viewType == SPACER_ITEM_TYPE) {
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.spacer_view_holder, parent, false);
                return new SpacerViewHolder(view);
            }

            view = LayoutInflater.from(parent.getContext()).inflate(R.layout.all_categories_view_holder, parent, false);

            if (onClickListener != null) {
                view.setOnClickListener(onClickListener);
            }

            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position != 0) {
                Allcategories category = categoriesArrayList.get(position - 1);

                String categorySize = category.getPages() +
                        " " +
                        StringUtils.declarationOfNum(Integer.parseInt(category.getPages()), pages) +
                        " " +
                        getString(R.string.in_this_category);

                        ((ViewHolder) holder).titleTextView.setText(category.getTitle());
                ((ViewHolder)holder).membersCountTextView.setText(categorySize);
            }
        }

        @Override
        public int getItemCount() {
            return categoriesArrayList == null ? 0 : categoriesArrayList.size() + 1;
        }

        private class SpacerViewHolder extends RecyclerView.ViewHolder {
            public SpacerViewHolder(View itemView) {
                super(itemView);
            }
        }

        protected class ViewHolder extends RecyclerView.ViewHolder {

            public TextView titleTextView, membersCountTextView;

            public ViewHolder(View itemView) {
                super(itemView);
                titleTextView = (TextView) itemView.findViewById(R.id.title_text_view);
                membersCountTextView = (TextView) itemView.findViewById(R.id.members_count_text_view);
            }
        }
    }
}
