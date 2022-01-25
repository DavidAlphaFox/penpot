/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */

 "use strict";

 describe("profile", () => {
   beforeEach(() => {
    cy.fixture('validuser.json').then((user) => {
        cy.login(user.email, user.password)
    });
     
   });
 
   it("open profile section", () => {
     cy.get(".profile").click();   
     cy.get('[data-e2e="profile-profile-opt"]').should("exist");
     cy.get('[data-e2e="profile-profile-opt"]').click();
     cy.get('[data-e2e="account-title"]').should("exist");
    });

    it("change profile name", () => {
      cy.get(".profile").click();   
      cy.get('[data-e2e="profile-profile-opt"]').click();
      cy.get('#fullname').should("exist");
      cy.get('#fullname').clear().type("New name").type('{enter}');
      cy.get(".banner.success").should("exist");
     });

     it("change profile email", () => {
      cy.get(".profile").click();   
      cy.get('[data-e2e="profile-profile-opt"]').click();
      cy.get('.change-email').should("exist");
      cy.get('.change-email').click();
      cy.get('[data-e2e="change-email-title"]').should("exist");
      cy.fixture('validuser.json').then((user) => {
        cy.get('#email-1').type(user.email);
        cy.get('#email-2').type(user.email);
      });
      cy.get('[data-e2e="change-email-submit"]').click();
      cy.get(".banner.info").should("exist");
     });

     it("type wrong email while trying to update should throw an error", () => {
      cy.get(".profile").click();   
      cy.get('[data-e2e="profile-profile-opt"]').click();
      cy.get('.change-email').click();
      cy.fixture('validuser.json').then((user) => {
        cy.get('#email-1').type(user.email);
      });
      cy.get('#email-2').type("bad@email.com");
      cy.get('[data-e2e="change-email-submit"]').click();
      cy.get('.error').should("exist");
     });

     it("open password section", () => {
      cy.get(".profile").click();   
      cy.get('[data-e2e="password-profile-opt"]').click();
      cy.get('.password-form').should("exist");
     });

     it("type old password wrong should throw an error", () => {
      cy.get(".profile").click();   
      cy.get('[data-e2e="password-profile-opt"]').click();
      cy.get('#password-old').type("badpassword");
      cy.get('#password-1').type("pretty-new-password");
      cy.get('#password-2').type("pretty-new-password");
      cy.get('[data-e2e="submit-password"]').click();
      cy.get('.error').should("exist");
     });

     it("type same old password should work", () => {
      cy.get(".profile").click();   
      cy.get('[data-e2e="password-profile-opt"]').click();
      cy.fixture('validuser.json').then((user) => {
        cy.get('#password-old').type(user.password);
        cy.get('#password-1').type(user.password);
        cy.get('#password-2').type(user.password);
      });
      cy.get('[data-e2e="submit-password"]').click();
      cy.get(".banner.success").should("exist");
     });

     it("open settings section", () => {
      cy.get(".profile").click();   
      cy.get('[data-e2e="profile-profile-opt"]').click();
      cy.get('[data-e2e="settings-profile"]').should("exist");
    });

     it("set lang to Spanish", () => {
      cy.get(".profile").click();   
      cy.get('[data-e2e="profile-profile-opt"]').click();
      cy.get('[data-e2e="settings-profile"]').click();
      cy.get('[data-e2e="setting-lang"]').should("exist");
      cy.get('[data-e2e="setting-lang"]').select("es");
      cy.get('[data-e2e="submit-lang-change"]').should("exist");
      cy.get('[data-e2e="submit-lang-change"]').click();
      cy.contains("Tu cuenta").should("exist");
     });

     it("set lang back to english", () => {
      cy.get(".profile").click();   
      cy.get('[data-e2e="profile-profile-opt"]').click();
      cy.get('[data-e2e="settings-profile"]').click();
      cy.get('[data-e2e="setting-lang"]').select("en");
      cy.get('[data-e2e="submit-lang-change"]').click();
      cy.contains("Your account").should("exist");
     });

     it("log out from app", () => {
      cy.get(".profile").click();   
      cy.get('[data-e2e="logout-profile-opt"]').should("exist");
      cy.get('[data-e2e="logout-profile-opt"]').click();
      cy.get('[data-e2e="login-title"]').should("exist");
     });

 });