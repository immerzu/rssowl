/*   **********************************************************************  **
 **   Copyright notice                                                       **
 **                                                                          **
 **   (c) 2005-2009 RSSOwl Development Team                                  **
 **   http://www.rssowl.org/                                                 **
 **                                                                          **
 **   All rights reserved                                                    **
 **                                                                          **
 **   This program and the accompanying materials are made available under   **
 **   the terms of the Eclipse Public License v1.0 which accompanies this    **
 **   distribution, and is available at:                                     **
 **   http://www.rssowl.org/legal/epl-v10.html                               **
 **                                                                          **
 **   A copy is found in the file epl-v10.html and important notices to the  **
 **   license from the team is found in the textfile LICENSE.txt distributed **
 **   in this package.                                                       **
 **                                                                          **
 **   This copyright notice MUST APPEAR in all copies of the file!           **
 **                                                                          **
 **   Contributors:                                                          **
 **     RSSOwl Development Team - initial API and implementation             **
 **                                                                          **
 **  **********************************************************************  */

package org.rssowl.ui.internal.actions;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkbenchWindowActionDelegate;
import org.rssowl.ui.internal.Activator;
import org.rssowl.ui.internal.OwlUI;
import org.rssowl.ui.internal.editors.feed.FeedView;
import org.rssowl.ui.internal.views.explorer.BookMarkExplorer;

/**
 * @author bpasero
 */
public class MarkAllNewsReadAction extends Action implements IWorkbenchWindowActionDelegate {
  private static final long EXPLORER_SELECTION_RECENT_WINDOW_MS = 1500L;
  private static volatile long fgLastExplorerSelectionUpdate;

  /** Action ID */
  public static final String ID = "org.rssowl.ui.MarkAllRead"; //$NON-NLS-1$

  /** Leave for reflection */
  public MarkAllNewsReadAction() {
    setText(Messages.MarkAllNewsReadAction_MARK_ALL_READ);
    setImageDescriptor(OwlUI.getImageDescriptor("icons/elcl16/mark_all_read.gif")); //$NON-NLS-1$
    setId(ID);
    setActionDefinitionId(ID);
  }

  /*
   * @see org.eclipse.jface.action.Action#run()
   */
  @Override
  public void run() {
    try {
      IStructuredSelection selection = getSelection();
      if (!selection.isEmpty()) {
        new MarkTypesReadAction(selection).run();
        return;
      }

      FeedView activeFeedView = OwlUI.getActiveFeedView();
      if (activeFeedView != null)
        new MarkTypesReadAction(new StructuredSelection(activeFeedView)).run();
    } catch (RuntimeException e) {
      Activator.getDefault().logError("Failed to mark all news as read.", e); //$NON-NLS-1$
    }
  }

  private IStructuredSelection getSelection() {
    BookMarkExplorer explorer = OwlUI.getOpenedBookMarkExplorer();
    if (explorer != null) {
      ISelection selection = explorer.getViewSite().getSelectionProvider().getSelection();
      if (selection instanceof IStructuredSelection) {
        IStructuredSelection structuredSelection = (IStructuredSelection) selection;
        if (!structuredSelection.isEmpty() && isRecentExplorerSelection(explorer))
          return structuredSelection;
      }
    }

    return StructuredSelection.EMPTY;
  }

  private boolean isRecentExplorerSelection(BookMarkExplorer explorer) {
    IWorkbenchPage page = OwlUI.getPage();
    if (page != null && page.getActivePart() == explorer)
      return true;

    long lastUpdate = fgLastExplorerSelectionUpdate;
    return lastUpdate > 0 && System.currentTimeMillis() - lastUpdate <= EXPLORER_SELECTION_RECENT_WINDOW_MS;
  }

  /*
   * @see org.eclipse.ui.IActionDelegate#run(org.eclipse.jface.action.IAction)
   */
  public void run(IAction action) {
    run();
  }

  /*
   * @see org.eclipse.ui.IWorkbenchWindowActionDelegate#dispose()
   */
  public void dispose() {}

  /*
   * @see org.eclipse.ui.IWorkbenchWindowActionDelegate#init(org.eclipse.ui.IWorkbenchWindow)
   */
  public void init(IWorkbenchWindow window) {}

  /*
   * @see org.eclipse.ui.IActionDelegate#selectionChanged(org.eclipse.jface.action.IAction, org.eclipse.jface.viewers.ISelection)
   */
  public void selectionChanged(IAction action, ISelection selection) {
    if (selection instanceof IStructuredSelection) {
      IWorkbenchPage page = OwlUI.getPage();
      if (page != null && page.getActivePart() instanceof BookMarkExplorer)
        fgLastExplorerSelectionUpdate = System.currentTimeMillis();
    }
  }
}
