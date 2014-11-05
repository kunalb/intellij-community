/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.push;

import com.intellij.dvcs.push.PushTargetPanel;
import com.intellij.dvcs.push.ui.PushTargetTextField;
import com.intellij.dvcs.push.ui.VcsEditableTextComponent;
import com.intellij.dvcs.push.ui.VcsLinkListener;
import com.intellij.dvcs.push.ui.VcsLinkedText;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.Function;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import git4idea.GitRemoteBranch;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.text.ParseException;
import java.util.Comparator;
import java.util.List;

public class GitPushTargetPanel extends PushTargetPanel<GitPushTarget> {

  private static final Logger LOG = Logger.getInstance(GitPushTargetPanel.class);

  private static final Comparator<GitRemoteBranch> REMOTE_BRANCH_COMPARATOR = new MyRemoteBranchComparator();
  private static final String SEPARATOR = " : ";

  @NotNull private final GitRepository myRepository;
  @NotNull private final VcsEditableTextComponent myTargetRenderedComponent;
  @NotNull private final PushTargetTextField myTargetTextField;
  @NotNull private final VcsLinkedText myRemoteRenderedComponent;

  @Nullable private GitPushTarget myCurrentTarget;
  @Nullable private String myError;
  @Nullable private Runnable myFireOnChangeAction;

  public GitPushTargetPanel(@NotNull GitRepository repository, @Nullable GitPushTarget defaultTarget) {
    myRepository = repository;

    myCurrentTarget = defaultTarget;

    String initialBranch = "";
    String initialRemote = "";
    if (defaultTarget == null) {
      if (repository.getCurrentBranch() == null) {
        myError = "Detached HEAD";
      }
      else if (repository.getRemotes().isEmpty()) {
        myError = "No remotes";
      }
      else if (repository.isFresh()) {
        myError = "Empty repository";
      }
      else {
        myError = "Can't push";
      }
    }
    else {
      initialBranch = getTextFieldText(defaultTarget);
      initialRemote = defaultTarget.getBranch().getRemote().getName();
    }
    myTargetRenderedComponent = new VcsEditableTextComponent("<a href=''>" + initialBranch + "</a>", null);
    myTargetTextField = new PushTargetTextField(repository.getProject(), getTargetNames(myRepository), initialBranch);
    myRemoteRenderedComponent = new VcsLinkedText("<a href=''>" + initialRemote + "</a>", new VcsLinkListener() {
      @Override
      public void hyperlinkActivated(@NotNull DefaultMutableTreeNode sourceNode, @NotNull MouseEvent event) {
        showRemoteSelector(event);
      }
    });

    setLayout(new BorderLayout());
    setOpaque(false);
    JPanel remoteAndSeparator = new JPanel(new BorderLayout());
    remoteAndSeparator.setOpaque(false);
    remoteAndSeparator.add(new JBLabel(myRemoteRenderedComponent.getText()), BorderLayout.CENTER);
    remoteAndSeparator.add(new JBLabel(SEPARATOR), BorderLayout.EAST);

    add(remoteAndSeparator, BorderLayout.WEST);
    add(myTargetTextField, BorderLayout.CENTER);
    updateTextField();
  }

  private void updateTextField() {
    myTargetTextField.setVisible(!myRepository.getRemotes().isEmpty());
  }

  private void showRemoteSelector(@NotNull MouseEvent event) {
    final List<String> remotes = getRemotes();
    if (remotes.size() <= 1) {
      return;
    }

    ListPopup popup = JBPopupFactory.getInstance().createListPopup(new BaseListPopupStep<String>(null, remotes) {
      @Override
      public PopupStep onChosen(String selectedValue, boolean finalChoice) {
        myRemoteRenderedComponent.updateLinkText(selectedValue);
        if (myFireOnChangeAction != null) {
          myFireOnChangeAction.run();
        }
        return super.onChosen(selectedValue, finalChoice);
      }
    });
    popup.show(new RelativePoint(event));
  }

  @NotNull
  private List<String> getRemotes() {
    return ContainerUtil.map(myRepository.getRemotes(), new Function<GitRemote, String>() {
      @Override
      public String fun(GitRemote remote) {
        return remote.getName();
      }
    });
  }

  @Override
  public void render(@NotNull ColoredTreeCellRenderer renderer, boolean isSelected) {
    if (myError != null) {
      renderer.append(myError, SimpleTextAttributes.ERROR_ATTRIBUTES);
    }
    else {
      String currentRemote = myRemoteRenderedComponent.getText();
      if (getRemotes().size() > 1) {
        myRemoteRenderedComponent.setSelected(isSelected);
        myRemoteRenderedComponent.render(renderer);
      }
      else {
        renderer.append(currentRemote, SimpleTextAttributes.REGULAR_ATTRIBUTES);
      }
      renderer.append(SEPARATOR, SimpleTextAttributes.REGULAR_ATTRIBUTES);

      GitPushTarget target = getValue();
      if (target.isNewBranchCreated()) {
        renderer.append("+", SimpleTextAttributes.SYNTHETIC_ATTRIBUTES, this);
      }
      myTargetRenderedComponent.setSelected(isSelected);
      myTargetRenderedComponent.render(renderer);
    }
  }

  @NotNull
  @Override
  public GitPushTarget getValue() {
    return ObjectUtils.assertNotNull(myCurrentTarget);
  }

  @NotNull
  private static String getTextFieldText(@Nullable GitPushTarget target) {
    return (target != null ? target.getBranch().getNameForRemoteOperations() : "");
  }

  @Override
  public void fireOnCancel() {
    myTargetTextField.setText(getTextFieldText(myCurrentTarget));
  }

  @Override
  public void fireOnChange() {
    if (myError != null) {
      return;
    }
    String remoteName = myRemoteRenderedComponent.getText();
    String branchName = myTargetTextField.getText();
    try {
      myCurrentTarget = GitPushTarget.parse(myRepository, remoteName, branchName);
      myTargetRenderedComponent.updateLinkText(branchName);
    }
    catch (ParseException e) {
      LOG.error("Invalid remote name shouldn't be allowed. [" + remoteName + ", " + branchName + "]", e);
    }
  }

  @Nullable
  @Override
  public ValidationInfo verify() {
    if (myError != null) {
      return new ValidationInfo(myError, myTargetTextField);
    }
    try {
      GitPushTarget.parse(myRepository, myRemoteRenderedComponent.getText(), myTargetTextField.getText());
      return null;
    }
    catch (ParseException e) {
      return new ValidationInfo(e.getMessage(), myTargetTextField);
    }
  }

  @SuppressWarnings("NullableProblems")
  @Override
  public void setFireOnChangeAction(@NotNull Runnable action) {
    myFireOnChangeAction = action;
  }

  @NotNull
  public static List<String> getTargetNames(@NotNull GitRepository repository) {
    List<GitRemoteBranch> remoteBranches = ContainerUtil.sorted(repository.getBranches().getRemoteBranches(), REMOTE_BRANCH_COMPARATOR);
    return ContainerUtil.map(remoteBranches, new Function<GitRemoteBranch, String>() {
      @Override
      public String fun(GitRemoteBranch branch) {
        return branch.getNameForRemoteOperations();
      }
    });
  }

  private static class MyRemoteBranchComparator implements Comparator<GitRemoteBranch> {
    @Override
    public int compare(@NotNull GitRemoteBranch o1, @NotNull GitRemoteBranch o2) {
      String remoteName1 = o1.getRemote().getName();
      String remoteName2 = o2.getRemote().getName();
      int remoteComparison = remoteName1.compareTo(remoteName2);
      if (remoteComparison != 0) {
        if (remoteName1.equals(GitRemote.ORIGIN_NAME)) {
          return -1;
        }
        if (remoteName2.equals(GitRemote.ORIGIN_NAME)) {
          return 1;
        }
        return remoteComparison;
      }
      return o1.getNameForLocalOperations().compareTo(o2.getNameForLocalOperations());
    }
  }
}
