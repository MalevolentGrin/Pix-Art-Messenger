package de.pixart.messenger.ui;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.content.res.Resources;
import android.databinding.DataBindingUtil;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import android.widget.Toast;

import org.openintents.openpgp.util.OpenPgpUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import de.pixart.messenger.Config;
import de.pixart.messenger.R;
import de.pixart.messenger.crypto.PgpEngine;
import de.pixart.messenger.databinding.ActivityMucDetailsBinding;
import de.pixart.messenger.databinding.ContactBinding;
import de.pixart.messenger.entities.Account;
import de.pixart.messenger.entities.Bookmark;
import de.pixart.messenger.entities.Contact;
import de.pixart.messenger.entities.Conversation;
import de.pixart.messenger.entities.MucOptions;
import de.pixart.messenger.entities.MucOptions.User;
import de.pixart.messenger.services.XmppConnectionService;
import de.pixart.messenger.services.XmppConnectionService.OnConversationUpdate;
import de.pixart.messenger.services.XmppConnectionService.OnMucRosterUpdate;
import de.pixart.messenger.ui.adapter.MediaAdapter;
import de.pixart.messenger.ui.interfaces.OnMediaLoaded;
import de.pixart.messenger.ui.util.Attachment;
import de.pixart.messenger.ui.util.GridManager;
import de.pixart.messenger.ui.util.MucDetailsContextMenuHelper;
import de.pixart.messenger.ui.util.MyLinkify;
import de.pixart.messenger.ui.util.SoftKeyboardUtils;
import de.pixart.messenger.utils.Compatibility;
import de.pixart.messenger.utils.EmojiWrapper;
import de.pixart.messenger.utils.MenuDoubleTabUtil;
import de.pixart.messenger.utils.StringUtils;
import de.pixart.messenger.utils.StylingHelper;
import de.pixart.messenger.utils.TimeframeUtils;
import de.pixart.messenger.utils.UIHelper;
import de.pixart.messenger.utils.XmppUri;
import rocks.xmpp.addr.Jid;

import static de.pixart.messenger.entities.Bookmark.printableValue;
import static de.pixart.messenger.utils.StringUtils.changed;

public class ConferenceDetailsActivity extends XmppActivity implements OnConversationUpdate, OnMucRosterUpdate, XmppConnectionService.OnAffiliationChanged, XmppConnectionService.OnRoleChanged, XmppConnectionService.OnConfigurationPushed, TextWatcher, OnMediaLoaded {
    public static final String ACTION_VIEW_MUC = "view_muc";
    private static final float INACTIVE_ALPHA = 0.4684f; //compromise between dark and light theme
    private Conversation mConversation;
    private OnClickListener inviteListener = new OnClickListener() {

        @Override
        public void onClick(View v) {
            inviteToConversation(mConversation);
        }
    };
    private OnClickListener destroyListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            final AlertDialog.Builder DestroyMucDialog = new AlertDialog.Builder(ConferenceDetailsActivity.this);
            DestroyMucDialog.setNegativeButton(getString(R.string.cancel), null);
            DestroyMucDialog.setTitle(getString(R.string.destroy_muc));
            DestroyMucDialog.setMessage(getString(R.string.destroy_muc_text, mConversation.getName()));
            DestroyMucDialog.setPositiveButton(getString(R.string.delete), (dialogInterface, i) -> {
                Intent intent = new Intent(xmppConnectionService, ConversationsActivity.class);
                intent.setAction(ConversationsActivity.ACTION_DESTROY_MUC);
                intent.putExtra("MUC_UUID", mConversation.getUuid());
                Log.d(Config.LOGTAG, "Sending DESTROY intent for " + mConversation.getName());
                startActivity(intent);
                overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                deleteBookmark();
                finish();
            });
            DestroyMucDialog.create().show();
        }
    };
    private ActivityMucDetailsBinding binding;
    private MediaAdapter mMediaAdapter;
    private String uuid = null;
    private User mSelectedUser = null;

    private boolean mAdvancedMode = false;

    private UiCallback<Conversation> renameCallback = new UiCallback<Conversation>() {
        @Override
        public void success(Conversation object) {
            runOnUiThread(() -> {
                Toast.makeText(ConferenceDetailsActivity.this, getString(R.string.your_nick_has_been_changed), Toast.LENGTH_SHORT).show();
                updateView();
            });

        }

        @Override
        public void error(final int errorCode, Conversation object) {
            runOnUiThread(() -> Toast.makeText(ConferenceDetailsActivity.this, getString(errorCode), Toast.LENGTH_SHORT).show());
        }

        @Override
        public void userInputRequried(PendingIntent pi, Conversation object) {

        }
    };

    private OnClickListener mNotifyStatusClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            AlertDialog.Builder builder = new AlertDialog.Builder(ConferenceDetailsActivity.this);
            builder.setTitle(R.string.pref_notification_settings);
            String[] choices = {
                    getString(R.string.notify_on_all_messages),
                    getString(R.string.notify_only_when_highlighted),
                    getString(R.string.notify_never)
            };
            final AtomicInteger choice;
            if (mConversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0) == Long.MAX_VALUE) {
                choice = new AtomicInteger(2);
            } else {
                choice = new AtomicInteger(mConversation.alwaysNotify() ? 0 : 1);
            }
            builder.setSingleChoiceItems(choices, choice.get(), (dialog, which) -> choice.set(which));
            builder.setNegativeButton(R.string.cancel, null);
            builder.setPositiveButton(R.string.ok, (dialog, which) -> {
                if (choice.get() == 2) {
                    AlertDialog.Builder builder1 = new AlertDialog.Builder(ConferenceDetailsActivity.this);
                    builder1.setTitle(R.string.disable_notifications);
                    final int[] durations = getResources().getIntArray(R.array.mute_options_durations);
                    final CharSequence[] labels = new CharSequence[durations.length];
                    for (int i = 0; i < durations.length; ++i) {
                        if (durations[i] == -1) {
                            labels[i] = getString(R.string.until_further_notice);
                        } else {
                            labels[i] = TimeframeUtils.resolve(ConferenceDetailsActivity.this, 1000L * durations[i]);
                        }
                    }
                    builder1.setItems(labels, (dialog1, which1) -> {
                        final long till;
                        if (durations[which1] == -1) {
                            till = Long.MAX_VALUE;
                        } else {
                            till = System.currentTimeMillis() + (durations[which1] * 1000);
                        }
                        mConversation.setMutedTill(till);
                        xmppConnectionService.updateConversation(mConversation);
                        updateView();
                    });
                    builder1.create().show();
                } else {
                    mConversation.setMutedTill(0);
                    mConversation.setAttribute(Conversation.ATTRIBUTE_ALWAYS_NOTIFY, String.valueOf(choice.get() == 0));
                }
                xmppConnectionService.updateConversation(mConversation);
                updateView();
            });
            builder.create().show();
        }
    };

    private OnClickListener mChangeConferenceSettings = new OnClickListener() {
        @Override
        public void onClick(View v) {
            final MucOptions mucOptions = mConversation.getMucOptions();
            AlertDialog.Builder builder = new AlertDialog.Builder(ConferenceDetailsActivity.this);
            builder.setTitle(R.string.conference_options);
            final String[] options;
            final boolean[] values;
            if (mAdvancedMode) {
                options = new String[]{
                        getString(R.string.members_only),
                        getString(R.string.moderated),
                        getString(R.string.non_anonymous)
                };
                values = new boolean[]{
                        mucOptions.membersOnly(),
                        mucOptions.moderated(),
                        mucOptions.nonanonymous()
                };
            } else {
                options = new String[]{
                        getString(R.string.members_only),
                        getString(R.string.non_anonymous)
                };
                values = new boolean[]{
                        mucOptions.membersOnly(),
                        mucOptions.nonanonymous()
                };
            }
            builder.setMultiChoiceItems(options, values, (dialog, which, isChecked) -> values[which] = isChecked);
            builder.setNegativeButton(R.string.cancel, null);
            builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
                if (!mucOptions.membersOnly() && values[0]) {
                    xmppConnectionService.changeAffiliationsInConference(mConversation,
                            MucOptions.Affiliation.NONE,
                            MucOptions.Affiliation.MEMBER);
                }
                Bundle options1 = new Bundle();
                options1.putString("muc#roomconfig_membersonly", values[0] ? "1" : "0");
                if (values.length == 2) {
                    options1.putString("muc#roomconfig_whois", values[1] ? "anyone" : "moderators");
                } else if (values.length == 3) {
                    options1.putString("muc#roomconfig_moderatedroom", values[1] ? "1" : "0");
                    options1.putString("muc#roomconfig_whois", values[2] ? "anyone" : "moderators");
                }
                options1.putString("muc#roomconfig_persistentroom", "1");
                final boolean whois = values.length == 2 ? values[1] : values[2];
                if (values[0] == whois) {
                    options1.putString("muc#roomconfig_publicroom", whois ? "0" : "1");
                }
                xmppConnectionService.pushConferenceConfiguration(mConversation,
                        options1,
                        ConferenceDetailsActivity.this);
            });
            builder.create().show();
        }
    };

    public static boolean cancelPotentialWork(User user, ImageView imageView) {
        final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final User old = bitmapWorkerTask.o;
            if (old == null || user != old) {
                bitmapWorkerTask.cancel(true);
            } else {
                return false;
            }
        }
        return true;
    }

    private static BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable) {
                final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
                return asyncDrawable.getBitmapWorkerTask();
            }
        }
        return null;
    }

    @Override
    public void onConversationUpdate() {
        refreshUi();
    }

    @Override
    public void onMucRosterUpdate() {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        updateView();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_muc_details);
        this.binding.changeConferenceButton.setOnClickListener(this.mChangeConferenceSettings);
        this.binding.invite.setOnClickListener(inviteListener);
        this.binding.invite.setVisibility(View.GONE);
        this.binding.invite.setOnClickListener(inviteListener);
        this.binding.destroy.setVisibility(View.GONE);
        this.binding.destroy.setOnClickListener(destroyListener);
        this.binding.leaveMuc.setVisibility(View.GONE);
        this.binding.addContactButton.setVisibility(View.GONE);
        this.binding.mucMoreDetails.setVisibility(View.GONE);
        setSupportActionBar((Toolbar) binding.toolbar);
        configureActionBar(getSupportActionBar());
        this.binding.editNickButton.setOnClickListener(v -> quickEdit(mConversation.getMucOptions().getActualNick(),
                R.string.nickname,
                value -> {
                    if (xmppConnectionService.renameInMuc(mConversation, value, renameCallback)) {
                        return null;
                    } else {
                        return getString(R.string.invalid_muc_nick);
                    }
                }));
        this.mAdvancedMode = getPreferences().getBoolean("advanced_muc_mode", false);
        this.binding.mucInfoMore.setVisibility(this.mAdvancedMode ? View.VISIBLE : View.GONE);
        this.binding.notificationStatusButton.setOnClickListener(this.mNotifyStatusClickListener);
        this.binding.detailsMucAvatar.setOnClickListener(v -> {
            final MucOptions mucOptions = mConversation.getMucOptions();
            if (!mucOptions.hasVCards()) {
                Toast.makeText(this, R.string.host_does_not_support_group_chat_avatars, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!mucOptions.getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                Toast.makeText(this, R.string.only_the_owner_can_change_group_chat_avatar, Toast.LENGTH_SHORT).show();
                return;
            }
            final Intent intent = new Intent(this, PublishGroupChatProfilePictureActivity.class);
            intent.putExtra("uuid", mConversation.getUuid());
            startActivity(intent);
        });
        this.binding.editMucNameButton.setOnClickListener(this::onMucEditButtonClicked);
        this.binding.mucEditTitle.addTextChangedListener(this);
        this.binding.mucEditSubject.addTextChangedListener(this);
        this.binding.mucEditSubject.addTextChangedListener(new StylingHelper.MessageEditorStyler(this.binding.mucEditSubject));
        mMediaAdapter = new MediaAdapter(this, R.dimen.media_size);
        this.binding.media.setAdapter(mMediaAdapter);
        GridManager.setupLayoutManager(this, this.binding.media, R.dimen.media_size);
    }

    @Override
    protected void onStart() {
        super.onStart();
        final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
        }
        binding.mediaWrapper.setVisibility(Compatibility.hasStoragePermission(this) ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        switch (menuItem.getItemId()) {
            case android.R.id.home:
                finish();
                break;
            case R.id.action_share_http:
                shareLink(true);
                break;
            case R.id.action_share_uri:
                shareLink(false);
                break;
            case R.id.action_advanced_mode:
                this.mAdvancedMode = !menuItem.isChecked();
                menuItem.setChecked(this.mAdvancedMode);
                getPreferences().edit().putBoolean("advanced_muc_mode", mAdvancedMode).apply();
                final boolean online = mConversation != null && mConversation.getMucOptions().online();
                this.binding.mucInfoMore.setVisibility(this.mAdvancedMode && online ? View.VISIBLE : View.GONE);
                invalidateOptionsMenu();
                updateView();
                break;
        }
        return super.onOptionsItemSelected(menuItem);
    }

    public void onMucEditButtonClicked(View v) {
        if (this.binding.mucEditor.getVisibility() == View.GONE) {
            final MucOptions mucOptions = mConversation.getMucOptions();
            this.binding.mucEditor.setVisibility(View.VISIBLE);
            this.binding.mucDisplay.setVisibility(View.GONE);
            this.binding.editMucNameButton.setImageResource(getThemeResource(R.attr.icon_cancel, R.drawable.ic_cancel_black_24dp));
            final String name = mucOptions.getName();
            this.binding.mucEditTitle.setText("");
            final boolean owner = mucOptions.getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER);
            if (owner || printableValue(name)) {
                this.binding.mucEditTitle.setVisibility(View.VISIBLE);
                if (name != null) {
                    this.binding.mucEditTitle.append(name);
                }
            } else {
                this.binding.mucEditTitle.setVisibility(View.GONE);
            }
            this.binding.mucEditTitle.setEnabled(owner);
            final String subject = mucOptions.getSubject();
            this.binding.mucEditSubject.setText("");
            if (subject != null) {
                this.binding.mucEditSubject.append(subject);
            }
            this.binding.mucEditSubject.setEnabled(mucOptions.canChangeSubject());
            if (!owner) {
                this.binding.mucEditSubject.requestFocus();
            }
        } else {
            String subject = this.binding.mucEditSubject.isEnabled() ? this.binding.mucEditSubject.getEditableText().toString().trim() : null;
            String name = this.binding.mucEditTitle.isEnabled() ? this.binding.mucEditTitle.getEditableText().toString().trim() : null;
            onMucInfoUpdated(subject, name);
            SoftKeyboardUtils.hideSoftKeyboard(this);
            hideEditor();
        }
    }

    private void hideEditor() {
        this.binding.mucEditor.setVisibility(View.GONE);
        this.binding.mucDisplay.setVisibility(View.VISIBLE);
        this.binding.editMucNameButton.setImageResource(getThemeResource(R.attr.icon_edit_body, R.drawable.ic_edit_black_24dp));
    }

    private void onMucInfoUpdated(String subject, String name) {
        final MucOptions mucOptions = mConversation.getMucOptions();
        if (mucOptions.canChangeSubject() && changed(mucOptions.getSubject(), subject)) {
            xmppConnectionService.pushSubjectToConference(mConversation, subject);
        }
        if (mucOptions.getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER) && changed(mucOptions.getName(), name)) {
            Bundle options = new Bundle();
            options.putString("muc#roomconfig_persistentroom", "1");
            options.putString("muc#roomconfig_roomname", StringUtils.nullOnEmpty(name));
            xmppConnectionService.pushConferenceConfiguration(mConversation, options, this);
        }
    }

    @Override
    protected String getShareableUri(boolean http) {
        if (mConversation != null) {
            if (http) {
                return Config.inviteMUCURL + XmppUri.lameUrlEncode(mConversation.getJid().asBareJid().toEscapedString());
            } else {
                return "xmpp:" + mConversation.getJid().asBareJid().toEscapedString() + "?join";
            }
        } else {
            return null;
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem menuItemAdvancedMode = menu.findItem(R.id.action_advanced_mode);
        menuItemAdvancedMode.setChecked(mAdvancedMode);
        if (mConversation == null) {
            return true;
        }
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.muc_details, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        Object tag = v.getTag();
        if (tag instanceof User) {
            getMenuInflater().inflate(R.menu.muc_details_context, menu);
            final User user = (User) tag;
            this.mSelectedUser = user;
            String name;
            final Contact contact = user.getContact();
            if (contact != null && contact.showInRoster()) {
                name = contact.getDisplayName();
            } else if (user.getRealJid() != null) {
                name = user.getRealJid().asBareJid().toString();
            } else {
                name = user.getName();
            }
            menu.setHeaderTitle(name);
            MucDetailsContextMenuHelper.configureMucDetailsContextMenu(this, menu, mConversation, user);
        }
        super.onCreateContextMenu(menu, v, menuInfo);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (!MucDetailsContextMenuHelper.onContextItemSelected(item, mSelectedUser, mConversation, this)) {
            return super.onContextItemSelected(item);
        }
        return true;
    }

    @Override
    public void onMediaLoaded(List<Attachment> attachments) {
        runOnUiThread(() -> {
            int limit = GridManager.getCurrentColumnCount(binding.media);
            mMediaAdapter.setAttachments(attachments.subList(0, Math.min(limit, attachments.size())));
            binding.mediaWrapper.setVisibility(attachments.size() > 0 ? View.VISIBLE : View.GONE);
        });
    }

    protected void saveAsBookmark() {
        xmppConnectionService.saveConversationAsBookmark(mConversation, mConversation.getMucOptions().getName());
        updateView();
    }

    protected void deleteBookmark() {
        Account account = mConversation.getAccount();
        Bookmark bookmark = mConversation.getBookmark();
        if (bookmark != null) {
            account.getBookmarks().remove(bookmark);
            bookmark.setConversation(null);
        }
        xmppConnectionService.pushBookmarks(account);
        updateView();
    }

    @Override
    void onBackendConnected() {
        if (mPendingConferenceInvite != null) {
            mPendingConferenceInvite.execute(this);
            mPendingConferenceInvite = null;
        }
        if (getIntent().getAction().equals(ACTION_VIEW_MUC)) {
            this.uuid = getIntent().getExtras().getString("uuid");
        }
        if (uuid != null) {
            this.mConversation = xmppConnectionService.findConversationByUuid(uuid);
            if (this.mConversation != null) {
                if (Compatibility.hasStoragePermission(this)) {
                    final int limit = GridManager.getCurrentColumnCount(this.binding.media);
                    xmppConnectionService.getAttachments(this.mConversation, limit, this);
                    this.binding.showMedia.setOnClickListener((v) -> MediaBrowserActivity.launch(this, mConversation));
                }
                updateView();
            }
        }
        this.binding.detailsMucAvatar.setImageBitmap(avatarService().get(mConversation, getPixel(Config.AVATAR_SIZE)));
    }

    @Override
    public void onBackPressed() {
        if (this.binding.mucEditor.getVisibility() == View.VISIBLE) {
            hideEditor();
        } else {
            super.onBackPressed();
        }
    }

    private void updateView() {
        invalidateOptionsMenu();
        if (mConversation == null) {
            return;
        }
        final MucOptions mucOptions = mConversation.getMucOptions();
        final User self = mucOptions.getSelf();
        String account;
        if (Config.DOMAIN_LOCK != null) {
            account = mConversation.getAccount().getJid().getLocal();
        } else {
            account = mConversation.getAccount().getJid().asBareJid().toString();
        }


        this.binding.editMucNameButton.setVisibility((self.getAffiliation().ranks(MucOptions.Affiliation.OWNER) || mucOptions.canChangeSubject()) ? View.VISIBLE : View.GONE);
        this.binding.detailsAccount.setText(getString(R.string.using_account, account));
        this.binding.jid.setText(mConversation.getJid().asBareJid().toEscapedString());
        if (xmppConnectionService.multipleAccounts()) {
            this.binding.detailsAccount.setVisibility(View.VISIBLE);
        } else {
            this.binding.detailsAccount.setVisibility(View.GONE);
        }
        this.binding.yourPhoto.setImageBitmap(avatarService().get(mConversation.getAccount(), getPixel(48)));
        String roomName = mucOptions.getName();
        String subject = mucOptions.getSubject();
        final boolean hasTitle;
        if (printableValue(roomName)) {
            this.binding.mucTitle.setText(EmojiWrapper.transform(roomName));
            this.binding.mucTitle.setVisibility(View.VISIBLE);
            hasTitle = true;
        } else if (!printableValue(subject)) {
            this.binding.mucTitle.setText(EmojiWrapper.transform(mConversation.getName()));
            hasTitle = true;
            this.binding.mucTitle.setVisibility(View.VISIBLE);
        } else {
            hasTitle = false;
            this.binding.mucTitle.setVisibility(View.GONE);
        }
        if (printableValue(subject)) {
            SpannableStringBuilder spannable = new SpannableStringBuilder(subject);
            StylingHelper.format(spannable, this.binding.mucSubject.getCurrentTextColor());
            MyLinkify.addLinks(spannable, false);
            this.binding.mucSubject.setText(EmojiWrapper.transform(spannable));
            this.binding.mucSubject.setTextAppearance(this, subject.length() > (hasTitle ? 128 : 196) ? R.style.TextAppearance_Conversations_Body1_Linkified : R.style.TextAppearance_Conversations_Subhead);
            this.binding.mucSubject.setAutoLinkMask(0);
            this.binding.mucSubject.setVisibility(View.VISIBLE);
        } else {
            this.binding.mucSubject.setVisibility(View.GONE);
        }
        this.binding.mucYourNick.setText(mucOptions.getActualNick());
        if (mucOptions.online()) {
            this.binding.mucMoreDetails.setVisibility(View.VISIBLE);
            this.binding.mucSettings.setVisibility(View.VISIBLE);
            this.binding.mucInfoMore.setVisibility(this.mAdvancedMode ? View.VISIBLE : View.GONE);
            this.binding.jid.setVisibility(this.mAdvancedMode ? View.VISIBLE : View.GONE);
            this.binding.mucRole.setVisibility(View.VISIBLE);
            this.binding.mucRole.setText(getStatus(self));
            if (mucOptions.membersOnly()) {
                this.binding.mucConferenceType.setText(R.string.private_conference);
            } else {
                this.binding.mucConferenceType.setText(R.string.public_conference);
            }
            if (mucOptions.mamSupport()) {
                this.binding.mucInfoMam.setText(R.string.server_info_available);
            } else {
                this.binding.mucInfoMam.setText(R.string.server_info_unavailable);
            }
            if (self.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                if (mAdvancedMode) {
                    this.binding.destroy.getBackground().setColorFilter(getWarningButtonColor(), PorterDuff.Mode.MULTIPLY);
                    this.binding.destroy.setVisibility(View.VISIBLE);
                } else {
                    this.binding.destroy.setVisibility(View.GONE);
                }
                this.binding.changeConferenceButton.setVisibility(View.VISIBLE);
            } else {
                this.binding.destroy.setVisibility(View.GONE);
                this.binding.changeConferenceButton.setVisibility(View.INVISIBLE);
            }
            this.binding.leaveMuc.setVisibility(View.VISIBLE);
            this.binding.leaveMuc.setOnClickListener(v1 -> {
                final AlertDialog.Builder LeaveMucDialog = new AlertDialog.Builder(ConferenceDetailsActivity.this);
                LeaveMucDialog.setTitle(getString(R.string.action_end_conversation_muc));
                LeaveMucDialog.setMessage(getString(R.string.leave_conference_warning));
                LeaveMucDialog.setNegativeButton(getString(R.string.cancel), null);
                LeaveMucDialog.setPositiveButton(getString(R.string.action_end_conversation_muc),
                        (dialog, which) -> {
                            startActivity(new Intent(xmppConnectionService, ConversationsActivity.class));
                            overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                            this.xmppConnectionService.archiveConversation(mConversation);
                            finish();
                        });
                LeaveMucDialog.create().show();
            });
            this.binding.leaveMuc.getBackground().setColorFilter(getWarningButtonColor(), PorterDuff.Mode.MULTIPLY);
            this.binding.addContactButton.setVisibility(View.VISIBLE);
            if (mConversation.getBookmark() != null) {
                this.binding.addContactButton.setText(R.string.delete_bookmark);
                this.binding.addContactButton.getBackground().setColorFilter(getWarningButtonColor(), PorterDuff.Mode.MULTIPLY);
                this.binding.addContactButton.setOnClickListener(v2 -> {
                    final AlertDialog.Builder deleteFromRosterDialog = new AlertDialog.Builder(ConferenceDetailsActivity.this);
                    deleteFromRosterDialog.setNegativeButton(getString(R.string.cancel), null);
                    deleteFromRosterDialog.setTitle(getString(R.string.action_delete_contact));
                    deleteFromRosterDialog.setMessage(getString(R.string.remove_bookmark_text, mConversation.getJid().toString()));
                    deleteFromRosterDialog.setPositiveButton(getString(R.string.delete),
                            (dialog, which) -> {
                                deleteBookmark();
                            });
                    deleteFromRosterDialog.create().show();
                });
            } else {
                this.binding.addContactButton.setText(R.string.save_as_bookmark);
                this.binding.addContactButton.getBackground().clearColorFilter();
                this.binding.addContactButton.setOnClickListener(v2 -> {
                    saveAsBookmark();
                });
            }
        } else {
            this.binding.mucMoreDetails.setVisibility(View.GONE);
            this.binding.mucInfoMore.setVisibility(View.GONE);
            this.binding.mucSettings.setVisibility(View.GONE);
        }

        int ic_notifications = getThemeResource(R.attr.icon_notifications, R.drawable.ic_notifications_black_24dp);
        int ic_notifications_off = getThemeResource(R.attr.icon_notifications_off, R.drawable.ic_notifications_off_black_24dp);
        int ic_notifications_paused = getThemeResource(R.attr.icon_notifications_paused, R.drawable.ic_notifications_paused_black_24dp);
        int ic_notifications_none = getThemeResource(R.attr.icon_notifications_none, R.drawable.ic_notifications_none_black_24dp);
        long mutedTill = mConversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0);
        if (mutedTill == Long.MAX_VALUE) {
            this.binding.notificationStatusText.setText(R.string.notify_never);
            this.binding.notificationStatusButton.setImageResource(ic_notifications_off);
        } else if (System.currentTimeMillis() < mutedTill) {
            this.binding.notificationStatusText.setText(R.string.notify_paused);
            this.binding.notificationStatusButton.setImageResource(ic_notifications_paused);
        } else if (mConversation.alwaysNotify()) {
            this.binding.notificationStatusText.setText(R.string.notify_on_all_messages);
            this.binding.notificationStatusButton.setImageResource(ic_notifications);
        } else {
            this.binding.notificationStatusText.setText(R.string.notify_only_when_highlighted);
            this.binding.notificationStatusButton.setImageResource(ic_notifications_none);
        }

        final LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        this.binding.mucMembers.removeAllViews();
        if (inflater == null) {
            return;
        }
        final ArrayList<User> users = mucOptions.getUsers();
        Collections.sort(users);
        for (final User user : users) {
            ContactBinding binding = DataBindingUtil.inflate(inflater, R.layout.contact, this.binding.mucMembers, false);
            this.setListItemBackgroundOnView(binding.getRoot());
            binding.getRoot().setOnClickListener(view1 -> highlightInMuc(mConversation, user.getName()));
            registerForContextMenu(binding.getRoot());
            binding.getRoot().setTag(user);
            if (mAdvancedMode && user.getPgpKeyId() != 0) {
                binding.key.setVisibility(View.VISIBLE);
                binding.key.setOnClickListener(v -> viewPgpKey(user));
                binding.key.setText(OpenPgpUtils.convertKeyIdToHex(user.getPgpKeyId()));
            }
            Contact contact = user.getContact();
            String name = user.getName();
            if (contact != null) {
                binding.contactDisplayName.setText(contact.getDisplayName());
                binding.contactJid.setText((name != null ? name + " \u2022 " : "") + getStatus(user));
            } else {
                binding.contactDisplayName.setText(name == null ? "" : name);
                binding.contactJid.setText(getStatus(user));

            }
            loadAvatar(user, binding.contactPhoto);
            if (user.getRole() == MucOptions.Role.NONE) {
                binding.contactJid.setAlpha(INACTIVE_ALPHA);
                binding.key.setAlpha(INACTIVE_ALPHA);
                binding.contactDisplayName.setAlpha(INACTIVE_ALPHA);
                binding.contactPhoto.setAlpha(INACTIVE_ALPHA);
            }
            this.binding.mucMembers.addView(binding.getRoot());
            if (mConversation.getMucOptions().canInvite()) {
                this.binding.invite.setVisibility(View.VISIBLE);
            } else {
                this.binding.invite.setVisibility(View.GONE);
            }
        }
    }

    private String getStatus(User user) {
        if (mAdvancedMode) {
            return getString(user.getAffiliation().getResId()) +
                    " (" + getString(user.getRole().getResId()) + ')';
        } else {
            return getString(user.getAffiliation().getResId());
        }
    }

    private void viewPgpKey(User user) {
        PgpEngine pgp = xmppConnectionService.getPgpEngine();
        if (pgp != null) {
            PendingIntent intent = pgp.getIntentForKey(user.getPgpKeyId());
            if (intent != null) {
                try {
                    startIntentSenderForResult(intent.getIntentSender(), 0, null, 0, 0, 0);
                } catch (SendIntentException ignored) {

                }
            }
        }
    }

    @Override
    public void onAffiliationChangedSuccessful(Jid jid) {
        refreshUi();
    }

    @Override
    public void onAffiliationChangeFailed(Jid jid, int resId) {
        displayToast(getString(resId, jid.asBareJid().toString()));
    }

    @Override
    public void onRoleChangedSuccessful(String nick) {

    }

    @Override
    public void onRoleChangeFailed(String nick, int resId) {
        displayToast(getString(resId, nick));
    }

    @Override
    public void onPushSucceeded() {
        displayToast(getString(R.string.modified_conference_options));
    }

    @Override
    public void onPushFailed() {
        displayToast(getString(R.string.could_not_modify_conference_options));
    }

    private void displayToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(ConferenceDetailsActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    public void loadAvatar(User user, ImageView imageView) {
        if (cancelPotentialWork(user, imageView)) {
            final Bitmap bm = avatarService().get(user, getPixel(48), true);
            if (bm != null) {
                cancelPotentialWork(user, imageView);
                imageView.setImageBitmap(bm);
                imageView.setBackgroundColor(0x00000000);
            } else {
                String seed = user.getRealJid() != null ? user.getRealJid().asBareJid().toString() : null;
                imageView.setBackgroundColor(UIHelper.getColorForName(seed == null ? user.getName() : seed));
                imageView.setImageDrawable(null);
                final BitmapWorkerTask task = new BitmapWorkerTask(imageView);
                final AsyncDrawable asyncDrawable = new AsyncDrawable(getResources(), null, task);
                imageView.setImageDrawable(asyncDrawable);
                try {
                    task.execute(user);
                } catch (final RejectedExecutionException ignored) {
                }
            }
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

    }

    @Override
    public void afterTextChanged(Editable s) {
        if (mConversation == null) {
            return;
        }
        final MucOptions mucOptions = mConversation.getMucOptions();
        if (this.binding.mucEditor.getVisibility() == View.VISIBLE) {
            boolean subjectChanged = changed(binding.mucEditSubject.getEditableText().toString(), mucOptions.getSubject());
            boolean nameChanged = changed(binding.mucEditTitle.getEditableText().toString(), mucOptions.getName());
            if (subjectChanged || nameChanged) {
                this.binding.editMucNameButton.setImageResource(getThemeResource(R.attr.icon_save, R.drawable.ic_save_black_24dp));
            } else {
                this.binding.editMucNameButton.setImageResource(getThemeResource(R.attr.icon_cancel, R.drawable.ic_cancel_black_24dp));
            }
        }
    }

    static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

        AsyncDrawable(Resources res, Bitmap bitmap, BitmapWorkerTask bitmapWorkerTask) {
            super(res, bitmap);
            bitmapWorkerTaskReference = new WeakReference<>(bitmapWorkerTask);
        }

        BitmapWorkerTask getBitmapWorkerTask() {
            return bitmapWorkerTaskReference.get();
        }
    }

    class BitmapWorkerTask extends AsyncTask<User, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewReference;
        private User o = null;

        private BitmapWorkerTask(ImageView imageView) {
            imageViewReference = new WeakReference<>(imageView);
        }

        @Override
        protected Bitmap doInBackground(User... params) {
            this.o = params[0];
            if (imageViewReference.get() == null) {
                return null;
            }
            return avatarService().get(this.o, getPixel(48), isCancelled());
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null && !isCancelled()) {
                final ImageView imageView = imageViewReference.get();
                if (imageView != null) {
                    imageView.setImageBitmap(bitmap);
                    imageView.setBackgroundColor(0x00000000);
                }
            }
        }
    }
}
