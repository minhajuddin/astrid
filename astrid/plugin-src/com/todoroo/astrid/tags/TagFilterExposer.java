/**
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.tags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Toast;

import com.timsu.astrid.R;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.sql.Criterion;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.sql.QueryTemplate;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.andlib.utility.DialogUtilities;
import com.todoroo.astrid.actfm.TagViewActivity;
import com.todoroo.astrid.actfm.sync.ActFmPreferenceService;
import com.todoroo.astrid.api.AstridApiConstants;
import com.todoroo.astrid.api.Filter;
import com.todoroo.astrid.api.FilterCategory;
import com.todoroo.astrid.api.FilterCategoryWithNewButton;
import com.todoroo.astrid.api.FilterListItem;
import com.todoroo.astrid.api.FilterWithCustomIntent;
import com.todoroo.astrid.api.FilterWithUpdate;
import com.todoroo.astrid.core.PluginServices;
import com.todoroo.astrid.dao.TaskDao.TaskCriteria;
import com.todoroo.astrid.data.Metadata;
import com.todoroo.astrid.data.TagData;
import com.todoroo.astrid.data.Update;
import com.todoroo.astrid.service.AstridDependencyInjector;
import com.todoroo.astrid.service.TagDataService;
import com.todoroo.astrid.tags.TagService.Tag;

/**
 * Exposes filters based on tags
 *
 * @author Tim Su <tim@todoroo.com>
 *
 */
public class TagFilterExposer extends BroadcastReceiver {

    private static final String TAG = "tag"; //$NON-NLS-1$

    @Autowired TagDataService tagDataService;

    private TagService tagService;

    /** Create filter from new tag object */
    @SuppressWarnings("nls")
    public static FilterWithCustomIntent filterFromTag(Context context, Tag tag, Criterion criterion) {
        String title = context.getString(R.string.tag_FEx_name, tag.tag);
        QueryTemplate tagTemplate = tag.queryTemplate(criterion);
        ContentValues contentValues = new ContentValues();
        contentValues.put(Metadata.KEY.name, TagService.KEY);
        contentValues.put(TagService.TAG.name, tag.tag);

        FilterWithUpdate filter = new FilterWithUpdate(tag.tag,
                title, tagTemplate,
                contentValues);
        if(tag.remoteId > 0) {
            filter.listingTitle += " (" + tag.count + ")";
            if(tag.count == 0)
                filter.color = Color.GRAY;
        }

        filter.contextMenuLabels = new String[] {
            context.getString(R.string.tag_cm_rename),
            context.getString(R.string.tag_cm_delete)
        };
        filter.contextMenuIntents = new Intent[] {
                newTagIntent(context, RenameTagActivity.class, tag),
                newTagIntent(context, DeleteTagActivity.class, tag)
        };
        filter.customTaskList = new ComponentName(ContextManager.getContext(), TagViewActivity.class);
        if(tag.image != null)
            filter.imageUrl = tag.image;
        if(tag.updateText != null)
            filter.updateText = tag.updateText;
        Bundle extras = new Bundle();
        extras.putString(TagViewActivity.EXTRA_TAG_NAME, tag.tag);
        extras.putLong(TagViewActivity.EXTRA_TAG_REMOTE_ID, tag.remoteId);
        filter.customExtras = extras;

        return filter;
    }

    /** Create a filter from tag data object */
    public static Filter filterFromTagData(Context context, TagData tagData) {
        Tag tag = new Tag(tagData.getValue(TagData.NAME),
                tagData.getValue(TagData.TASK_COUNT),
                tagData.getValue(TagData.REMOTE_ID));
        return filterFromTag(context, tag, TaskCriteria.activeAndVisible());
    }

    private static Intent newTagIntent(Context context, Class<? extends Activity> activity, Tag tag) {
        Intent ret = new Intent(context, activity);
        ret.putExtra(TAG, tag.tag);
        return ret;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        DependencyInjectionService.getInstance().inject(this);
        ContextManager.setContext(context);
        tagService = TagService.getInstance();

        ArrayList<FilterListItem> list = new ArrayList<FilterListItem>();

        // --- untagged
        addTags(list);

        // transmit filter list
        FilterListItem[] listAsArray = list.toArray(new FilterListItem[list.size()]);
        Intent broadcastIntent = new Intent(AstridApiConstants.BROADCAST_SEND_FILTERS);
        broadcastIntent.putExtra(AstridApiConstants.EXTRAS_RESPONSE, listAsArray);
        broadcastIntent.putExtra(AstridApiConstants.EXTRAS_ADDON, TagsPlugin.IDENTIFIER);
        context.sendBroadcast(broadcastIntent, AstridApiConstants.PERMISSION_READ);
    }

    private void addTags(ArrayList<FilterListItem> list) {
        HashMap<String, Tag> tags = new HashMap<String, Tag>();

        Tag[] tagsByAlpha = tagService.getGroupedTags(TagService.GROUPED_TAGS_BY_ALPHA,
                TaskCriteria.activeAndVisible());
        for(Tag tag : tagsByAlpha)
            if(!TextUtils.isEmpty(tag.tag))
                tags.put(tag.tag, tag);

        TodorooCursor<TagData> cursor = tagDataService.query(Query.select(TagData.PROPERTIES));
        try {
            TagData tagData = new TagData();
            for(cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                tagData.readFromCursor(cursor);
                String tagName = tagData.getValue(TagData.NAME).trim();
                Tag tag = new Tag(tagData);
                if(tagData.getValue(TagData.DELETION_DATE) > 0 && !tags.containsKey(tagName)) continue;
                if(TextUtils.isEmpty(tag.tag))
                    continue;
                tags.put(tagName, tag);

                Update update = tagDataService.getLatestUpdate(tagData);
                if(update != null)
                    tag.updateText = ActFmPreferenceService.updateToString(update);
            }
        } finally {
            cursor.close();
        }

        ArrayList<Tag> tagList = new ArrayList<Tag>(tags.values());
        Collections.sort(tagList,
                new Comparator<Tag>() {
            @Override
            public int compare(Tag object1, Tag object2) {
                return object1.tag.compareToIgnoreCase(object2.tag);
            }
        });
        for(Iterator<Entry<String, Tag>> i = tags.entrySet().iterator(); i.hasNext(); ) {
            Entry<String, Tag> entry = i.next();
            if(TextUtils.isEmpty(entry.getValue().tag))
                i.remove();
        }

        list.add(filterFromTags(tagList.toArray(new Tag[tagList.size()]),
                R.string.tag_FEx_header));
    }

    private FilterCategory filterFromTags(Tag[] tags, int name) {
        Filter[] filters = new Filter[tags.length + 1];
        Resources r = ContextManager.getContext().getResources();

        Filter untagged = new Filter(r.getString(R.string.tag_FEx_untagged),
                r.getString(R.string.tag_FEx_untagged),
                tagService.untaggedTemplate(),
                null);
        untagged.listingIcon = ((BitmapDrawable)r.getDrawable(R.drawable.gl_lists)).getBitmap();
        filters[0] = untagged;

        Context context = ContextManager.getContext();
        for(int i = 0; i < tags.length; i++)
            filters[i + 1] = filterFromTag(context, tags[i], TaskCriteria.activeAndVisible());
        FilterCategoryWithNewButton filter = new FilterCategoryWithNewButton(context.getString(name), filters);
        filter.label = r.getString(R.string.tag_FEx_add_new);
        filter.intent = PendingIntent.getActivity(context, 0,
                TagsPlugin.newTagDialog(context), 0);
        return filter;
    }

    // --- tag manipulation activities

    public abstract static class TagActivity extends Activity {

        protected String tag;

        @Autowired public TagService tagService;
        @Autowired public TagDataService tagDataService;

        static {
            AstridDependencyInjector.initialize();
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setTheme(android.R.style.Theme_Dialog);

            tag = getIntent().getStringExtra(TAG);
            if(tag == null) {
                finish();
                return;
            }
            DependencyInjectionService.getInstance().inject(this);


            TagData tagData = tagDataService.getTag(tag, TagData.MEMBER_COUNT);
            if(tagData != null && tagData.getValue(TagData.MEMBER_COUNT) > 0) {
                DialogUtilities.okDialog(this, getString(R.string.actfm_tag_operation_disabled), getCancelListener());
                return;
            }
            showDialog();
        }

        protected DialogInterface.OnClickListener getOkListener() {
            return new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        if (ok()) {
                            setResult(RESULT_OK);
                        } else {
                            toastNoChanges();
                            setResult(RESULT_CANCELED);
                        }
                    } finally {
                        finish();
                    }
                }
            };
        }

        protected DialogInterface.OnClickListener getCancelListener() {
            return new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        toastNoChanges();
                    } finally {
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                }

            };
        }

        private void toastNoChanges() {
            Toast.makeText(this, R.string.TEA_no_tags_modified,
                        Toast.LENGTH_SHORT).show();
        }

        protected abstract void showDialog();

        protected abstract boolean ok();
    }

    public static class DeleteTagActivity extends TagActivity {

        @Override
        protected void showDialog() {
            DialogUtilities.okCancelDialog(this, getString(R.string.DLG_delete_this_tag_question, tag), getOkListener(), getCancelListener());
        }

        @Override
        protected boolean ok() {
            int deleted = tagService.delete(tag);
            TagData tagData = PluginServices.getTagDataService().getTag(tag, TagData.ID, TagData.DELETION_DATE);
            if(tagData != null) {
                tagData.setValue(TagData.DELETION_DATE, DateUtilities.now());
                PluginServices.getTagDataService().save(tagData);
            }
            Toast.makeText(this, getString(R.string.TEA_tags_deleted, tag, deleted),
                    Toast.LENGTH_SHORT).show();
            return true;
        }

    }

    public static class RenameTagActivity extends TagActivity {

        private EditText editor;

        @Override
        protected void showDialog() {
            editor = new EditText(this);
            DialogUtilities.viewDialog(this, getString(R.string.DLG_rename_this_tag_header, tag), editor, getOkListener(), getCancelListener());
        }

        @Override
        protected boolean ok() {
            if(editor == null)
                return false;

            String text = editor.getText().toString();
            if (text == null || text.length() == 0) {
                return false;
            } else {
                int renamed = tagService.rename(tag, text);
                Toast.makeText(this, getString(R.string.TEA_tags_renamed, tag, text, renamed),
                        Toast.LENGTH_SHORT).show();
                return true;
            }
        }
    }

}
