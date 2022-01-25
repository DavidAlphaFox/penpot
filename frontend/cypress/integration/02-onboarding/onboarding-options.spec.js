/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */

 "use strict";

 describe("onboarding options solo or team", () => {
   beforeEach(() => {
    cy.demoLogin();
    cy.get(".modal-right button").click();    
    cy.get(".onboarding button").click();
    cy.get(".onboarding .skip").click();
   });
 
   it("choose solo option", () => {
    cy.get(".onboarding").should("contain", "Welcome to Penpot");
    cy.get("[data-e2e=fly-solo-button]").click();
    cy.contains("Start designing").should("exist");
   });

   it("choose team option and cancel", () => {
    cy.get(".onboarding").should("contain", "Welcome to Penpot");
    cy.get("[data-e2e=team-up-button]").click();
    cy.contains("Team up").should("exist");
    cy.get("button").click();
    cy.get(".onboarding").should("contain", "Welcome to Penpot");
   });

   it("choose team option, set team name and cancel", () => {
    cy.get(".onboarding").should("contain", "Welcome to Penpot");
    cy.get("[data-e2e=team-up-button]").click();
    cy.contains("Team up").should("exist");
    cy.get("#name").type("test team");
    cy.get("input[type=submit]").first().click();
    cy.get("#email").should("exist");
    cy.get("button").click();
    cy.get(".onboarding").should("contain", "Welcome to Penpot");
   });

   it("choose team option, set team name and skip", () => {
    cy.get(".onboarding").should("contain", "Welcome to Penpot");
    cy.get("[data-e2e=team-up-button]").click();
    cy.contains("Team up").should("exist");
    cy.get("#name").type("test team");
    cy.get("input[type=submit]").first().click();
    cy.get("#email").should("exist");
    cy.get(".skip-action").click();
    cy.contains("Start designing").should("exist");
   });

   it.only("choose team option, set team name and invite", () => {
    cy.get(".onboarding").should("contain", "Welcome to Penpot");
    cy.get("[data-e2e=team-up-button]").click();
    cy.contains("Team up").should("exist");
    cy.get("#name").type("test team");
    cy.get("input[type=submit]").first().click();
    cy.get("#email").should("exist");
    cy.get("#email").type("test@test.com");
    cy.get("input[type=submit]").first().click();
    cy.contains("Start designing").should("exist");
   });



 });
 
 