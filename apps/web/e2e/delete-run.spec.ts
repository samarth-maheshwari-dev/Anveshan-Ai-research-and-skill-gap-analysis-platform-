import { expect, test } from "@playwright/test";

import { fakeRun, installFakeApi } from "./fake-api";

test("T-17-05 a stopped research can be deleted from the dashboard after confirming; a running one has no delete", async ({
  page,
  context,
}) => {
  await installFakeApi(page, context, {
    signedIn: true,
    runs: [
      fakeRun({ id: "r-stopped", status: "CANCELLED", topic: "Old stopped research about campus apps" }),
      fakeRun({ id: "r-live", status: "RUNNING", topic: "Research that is running right now" }),
    ],
  });
  await page.goto("/dashboard");
  const list = page.getByRole("region", { name: "Your research" });
  await expect(list.getByText("Old stopped research about campus apps")).toBeVisible();
  await expect(list.getByRole("button", { name: /Delete "Research that is running right now"/ })).toHaveCount(0);

  await list.getByRole("button", { name: /Delete "Old stopped research about campus apps"/ }).click();
  const dialog = page.getByRole("alertdialog", { name: "Delete this research?" });
  await dialog.getByRole("button", { name: "Keep it" }).click();
  await expect(list.getByText("Old stopped research about campus apps")).toBeVisible();

  await list.getByRole("button", { name: /Delete "Old stopped research about campus apps"/ }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Delete" }).click();
  await expect(list.getByText("Old stopped research about campus apps")).toHaveCount(0);
  await expect(list.getByText("Research that is running right now")).toBeVisible();
});
