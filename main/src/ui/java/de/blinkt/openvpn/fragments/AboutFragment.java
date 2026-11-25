/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.fragments;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.minab.MinabUpdateChecker;

public class AboutFragment extends Fragment implements View.OnClickListener {

    private View mUpdateCard;
    private View mProgressRow;
    private TextView mUpdateBadge;
    private TextView mUpdateStatus;
    private Button mCheckButton;
    private Button mDownloadButton;
    private Button mAssetsButton;
    private String mInstalledVersion = "";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.about, container, false);
        try {
            TextView ver = v.findViewById(R.id.version);
            String version;
            try {
                PackageInfo packageinfo = requireActivity().getPackageManager()
                        .getPackageInfo(requireActivity().getPackageName(), 0);
                version = packageinfo.versionName;
            } catch (NameNotFoundException e) {
                version = "—";
            }
            mInstalledVersion = version;
            if (ver != null) {
                ver.setText(getString(R.string.minab_about_version_badge, version));
            }

            mUpdateCard = v.findViewById(R.id.minab_about_update_card);
            mProgressRow = v.findViewById(R.id.minab_update_progress_row);
            mUpdateBadge = v.findViewById(R.id.minab_update_badge_pill);
            mUpdateStatus = v.findViewById(R.id.minab_update_status);
            mCheckButton = v.findViewById(R.id.minab_about_check_update);
            mDownloadButton = v.findViewById(R.id.minab_about_download_btn);
            mAssetsButton = v.findViewById(R.id.minab_about_assets_btn);

            if (mDownloadButton != null) {
                mDownloadButton.setOnClickListener(view ->
                        MinabUpdateChecker.openLatestApkDownload(requireContext()));
            }
            if (mAssetsButton != null) {
                mAssetsButton.setOnClickListener(view ->
                        MinabUpdateChecker.openLatestRelease(requireContext()));
            }

            // Cached background check on first display
            MinabUpdateChecker.check(requireActivity(), false, (latestTag, available) -> {
                if (!isAdded()) return;
                renderUpdateState(latestTag, available, false);
            });

            if (mCheckButton != null) {
                mCheckButton.setOnClickListener(view -> runManualUpdateCheck());
            }

            Button githubBtn = v.findViewById(R.id.minab_about_github_btn);
            if (githubBtn != null) {
                githubBtn.setOnClickListener(view ->
                        MinabUpdateChecker.openUrl(requireContext(), MinabUpdateChecker.REPO_URL));
            }
            Button releasesBtn = v.findViewById(R.id.minab_about_releases_btn);
            if (releasesBtn != null) {
                releasesBtn.setOnClickListener(view ->
                        MinabUpdateChecker.openReleases(requireContext()));
            }

            ViewCompat.setOnApplyWindowInsetsListener(v, (view, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                        view.getPaddingRight(), insets.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        } catch (Exception e) {
            android.util.Log.e("AboutFragment", "Minab: onCreateView failed", e);
        }
        return v;
    }

    private void runManualUpdateCheck() {
        if (!isAdded()) return;
        if (mCheckButton != null) mCheckButton.setEnabled(false);
        if (mProgressRow != null) mProgressRow.setVisibility(View.VISIBLE);
        if (mUpdateStatus != null) mUpdateStatus.setVisibility(View.GONE);
        if (mDownloadButton != null) mDownloadButton.setVisibility(View.GONE);
        if (mAssetsButton != null) mAssetsButton.setVisibility(View.GONE);

        MinabUpdateChecker.check(requireActivity(), true, (latestTag, available) -> {
            if (!isAdded()) return;
            if (mCheckButton != null) mCheckButton.setEnabled(true);
            if (mProgressRow != null) mProgressRow.setVisibility(View.GONE);
            renderUpdateState(latestTag, available, true);
        });
    }

    private void renderUpdateState(String latestTag, boolean available, boolean manual) {
        if (!isAdded() || mUpdateStatus == null) return;
        if (mProgressRow != null) mProgressRow.setVisibility(View.GONE);

        if (latestTag == null) {
            if (mUpdateBadge != null) mUpdateBadge.setVisibility(View.GONE);
            if (mDownloadButton != null) mDownloadButton.setVisibility(View.GONE);
            if (mAssetsButton != null) mAssetsButton.setVisibility(View.GONE);
            if (mUpdateCard != null) mUpdateCard.setBackgroundResource(R.drawable.minab_card_bg);
            if (manual) {
                mUpdateStatus.setText(getString(R.string.minab_update_failed));
                mUpdateStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.minab_danger));
                mUpdateStatus.setVisibility(View.VISIBLE);
            } else {
                mUpdateStatus.setVisibility(View.GONE);
            }
        } else if (available) {
            if (mUpdateCard != null) {
                mUpdateCard.setBackgroundResource(R.drawable.minab_connect_card_connected);
            }
            if (mUpdateBadge != null) {
                mUpdateBadge.setText(latestTag);
                mUpdateBadge.setVisibility(View.VISIBLE);
            }
            mUpdateStatus.setText(getString(R.string.minab_about_update_ready, latestTag));
            mUpdateStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.minab_connected));
            mUpdateStatus.setVisibility(View.VISIBLE);
            if (mDownloadButton != null) {
                mDownloadButton.setText(getString(R.string.minab_about_download_latest, latestTag));
                mDownloadButton.setVisibility(View.VISIBLE);
            }
            if (mAssetsButton != null) {
                mAssetsButton.setVisibility(View.VISIBLE);
            }
        } else {
            if (mUpdateCard != null) mUpdateCard.setBackgroundResource(R.drawable.minab_card_bg);
            if (mUpdateBadge != null) {
                mUpdateBadge.setText(latestTag);
                mUpdateBadge.setVisibility(View.VISIBLE);
            }
            mUpdateStatus.setText(getString(R.string.minab_about_up_to_date, mInstalledVersion));
            mUpdateStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.minab_connected));
            mUpdateStatus.setVisibility(View.VISIBLE);
            if (mDownloadButton != null) mDownloadButton.setVisibility(View.GONE);
            if (mAssetsButton != null) mAssetsButton.setVisibility(View.GONE);
        }
    }

    @Override
    public void onClick(View v) {
    }
}
