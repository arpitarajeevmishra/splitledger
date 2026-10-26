package com.splitledger.demo;

import com.splitledger.balance.application.BalanceService;
import com.splitledger.balance.domain.Transfer;
import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.expense.application.ExpenseService;
import com.splitledger.expense.domain.SplitType;
import com.splitledger.group.domain.ExpenseGroup;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.domain.Role;
import com.splitledger.group.infra.GroupMemberRepository;
import com.splitledger.group.infra.GroupRepository;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.UserRepository;
import com.splitledger.settlement.application.SettlementService;
import com.splitledger.settlement.domain.Settlement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A group with a history, built from nothing (SL-705).
 *
 * <p>Written once and used twice by design: the k6 scenarios need a group whose balances are
 * worth computing, and S9's guest tour needs a visitor to land on something that is visibly
 * doing arithmetic rather than an empty state (SL-904). A fixture that existed only inside
 * a load test would have to be built a second time for the tour, and the two would drift.
 *
 * <p><b>Idempotent, keyed on the demo email.</b> Re-running it finds the account and stops.
 * That matters more than it sounds: this is the one piece of code whose whole purpose is to
 * write plausible data into a database, so the question "could this ever touch a real
 * account" has to have an obviously-yes-or-no answer. It only ever reads by a hardcoded
 * demo address and only ever creates when that address is absent.
 *
 * <p>It goes through the ordinary services rather than inserting rows. Seeding by INSERT
 * would be faster and would produce data no real flow could have produced -- splits that do
 * not sum, postings that do not balance -- which is precisely what a load test and a demo
 * must not be measuring.
 */
@Component
public class DemoSeed {

    private static final Logger log = LoggerFactory.getLogger(DemoSeed.class);

    /** The identity of the demo data. Nothing else distinguishes it from a real group. */
    public static final String OWNER_EMAIL = "demo@splitledger.invalid";
    private static final String SECOND_EMAIL = "demo-priya@splitledger.invalid";
    private static final String THIRD_EMAIL = "demo-rahul@splitledger.invalid";

    /**
     * {@code .invalid} is reserved by RFC 2606 and can never be registered, so the demo
     * accounts cannot collide with a real signup and nothing addressed to them can leave.
     */
    private static final List<String> EMAILS = List.of(OWNER_EMAIL, SECOND_EMAIL, THIRD_EMAIL);

    private final UserRepository users;
    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final ExpenseService expenses;
    private final SettlementService settlements;
    private final BalanceService balances;
    private final PasswordEncoder passwords;
    private final Clock clock;

    @Autowired
    DemoSeed(UserRepository users, GroupRepository groups, GroupMemberRepository members,
            ExpenseService expenses, SettlementService settlements, BalanceService balances,
            PasswordEncoder passwords) {
        this(users, groups, members, expenses, settlements, balances, passwords,
                Clock.systemUTC());
    }

    /** Visible for testing. */
    DemoSeed(UserRepository users, GroupRepository groups, GroupMemberRepository members,
            ExpenseService expenses, SettlementService settlements, BalanceService balances,
            PasswordEncoder passwords, Clock clock) {
        this.users = users;
        this.groups = groups;
        this.members = members;
        this.expenses = expenses;
        this.settlements = settlements;
        this.balances = balances;
        this.passwords = passwords;
        this.clock = clock;
    }

    /** What a caller needs to drive the seeded group: the group, and who is in it. */
    public record Seeded(UUID groupId, UUID owner, UUID second, UUID third, boolean created) {
    }

    /**
     * @param password what the demo account signs in with; the caller owns that decision,
     *     because a password hardcoded here would be a password in the repository
     */
    @Transactional
    public Seeded seed(String password) {
        if (users.findByEmail(OWNER_EMAIL).isPresent()) {
            log.info("Demo data already present; leaving it alone");
            return describeExisting();
        }

        Instant now = Instant.now(clock);
        String hash = passwords.encode(password);
        UUID owner = users.save(User.register(OWNER_EMAIL, "Demo User", hash, now)).id();
        UUID second = users.save(User.register(SECOND_EMAIL, "Priya Venkataraman", hash, now)).id();
        UUID third = users.save(User.register(THIRD_EMAIL, "Rahul Mehta", hash, now)).id();

        ExpenseGroup group = groups.save(ExpenseGroup.create("Goa trip", "INR", owner, now));
        members.save(GroupMember.join(group.id(), owner, Role.ADMIN, now));
        members.save(GroupMember.join(group.id(), second, Role.MEMBER, now));
        members.save(GroupMember.join(group.id(), third, Role.MEMBER, now));

        // One of each split type, because the thing worth showing -- and worth measuring --
        // is that the allocation is doing real work, including the odd paisa that three
        // does not divide into.
        LocalDate day = LocalDate.now(clock);
        expenses.record(group.id(), owner, "Beach house", 2_400_000L, owner, day.minusDays(6),
                SplitType.EQUAL, everyone(owner, second, third, null));
        expenses.record(group.id(), second, "Flights", 3_100_000L, second, day.minusDays(5),
                SplitType.EXACT, List.of(
                        new ExpenseService.Participant(owner, 1_100_000L),
                        new ExpenseService.Participant(second, 1_000_000L),
                        new ExpenseService.Participant(third, 1_000_000L)));
        expenses.record(group.id(), third, "Scooter hire", 450_000L, third, day.minusDays(4),
                SplitType.PERCENT, List.of(
                        new ExpenseService.Participant(owner, 5000L),
                        new ExpenseService.Participant(second, 3000L),
                        new ExpenseService.Participant(third, 2000L)));
        // 1000 / 3 in shares: somebody absorbs the extra paisa, which is the case a demo
        // should show rather than hide behind round numbers.
        expenses.record(group.id(), owner, "Dinner at Martin's", 1_000_01L, owner,
                day.minusDays(3), SplitType.SHARES, List.of(
                        new ExpenseService.Participant(owner, 1L),
                        new ExpenseService.Participant(second, 1L),
                        new ExpenseService.Participant(third, 1L)));

        // A settled debt as well as outstanding ones, so the activity feed and the payments
        // list both have something in them and the simplified plan has work left to do.
        //
        // Who pays whom is taken from the plan rather than written down here. The first
        // attempt hardcoded owner -> second and was refused, because after those four
        // expenses the owner is owed money rather than owing it -- which is not obvious
        // from reading them and changes the moment any amount does. Asking the plan is
        // self-correcting, and it exercises the simplification on the way past.
        Transfer settle = balances.simplified(group.id(), owner).getFirst();
        Settlement paid = settlements.create(group.id(), settle.from(), settle.to(),
                settle.amount().paise(), PaymentMethod.CASH);
        settlements.confirm(paid.id(), settle.to());

        log.info("Seeded the demo group {} with 3 members, 4 expenses and 1 settlement",
                group.id());
        return new Seeded(group.id(), owner, second, third, true);
    }

    private List<ExpenseService.Participant> everyone(UUID a, UUID b, UUID c, Long value) {
        return List.of(new ExpenseService.Participant(a, value),
                new ExpenseService.Participant(b, value),
                new ExpenseService.Participant(c, value));
    }

    /**
     * The ids of data somebody else already seeded.
     *
     * <p>Needed because a caller that finds the demo already present still has to be told
     * where it is -- returning nothing would make "seed, then use it" work only on the
     * first run of the process, which is the one case nobody tests twice.
     */
    private Seeded describeExisting() {
        UUID owner = users.findByEmail(OWNER_EMAIL).orElseThrow().id();
        UUID second = users.findByEmail(SECOND_EMAIL).orElseThrow().id();
        UUID third = users.findByEmail(THIRD_EMAIL).orElseThrow().id();
        UUID groupId = groups.findAllForMember(owner).stream()
                .map(ExpenseGroup::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The demo account exists but is in no group; seed data is half-written"));
        return new Seeded(groupId, owner, second, third, false);
    }

    /** The addresses this seed owns, for anything that has to tell demo data from real. */
    public static List<String> demoEmails() {
        return EMAILS;
    }
}
